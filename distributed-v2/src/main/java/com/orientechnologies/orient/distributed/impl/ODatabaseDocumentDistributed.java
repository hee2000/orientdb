package com.orientechnologies.orient.distributed.impl;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.orient.client.remote.message.tx.ORecordOperationRequest;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.compression.impl.OZIPCompressionUtil;
import com.orientechnologies.orient.core.db.*;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentEmbedded;
import com.orientechnologies.orient.core.db.record.OClassTrigger;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.enterprise.OEnterpriseEndpoint;
import com.orientechnologies.orient.core.exception.*;
import com.orientechnologies.orient.core.hook.ORecordHook;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.index.OClassIndexManager;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.metadata.OMetadataDefault;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OImmutableClass;
import com.orientechnologies.orient.core.metadata.schema.OImmutableSchema;
import com.orientechnologies.orient.core.metadata.schema.OView;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.metadata.security.ORule;
import com.orientechnologies.orient.core.metadata.sequence.OSequenceAction;
import com.orientechnologies.orient.core.metadata.sequence.OSequenceLibraryProxy;
import com.orientechnologies.orient.core.metadata.sequence.OSequenceLimitReachedException;
import com.orientechnologies.orient.core.query.live.OLiveQueryHook;
import com.orientechnologies.orient.core.query.live.OLiveQueryHookV2;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;
import com.orientechnologies.orient.core.schedule.OScheduledEvent;
import com.orientechnologies.orient.core.sql.executor.OExecutionPlan;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import com.orientechnologies.orient.core.storage.OAutoshardedStorage;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;
import com.orientechnologies.orient.core.storage.ORecordMetadata;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.cluster.OPaginatedCluster;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.tx.OTransactionAbstract;
import com.orientechnologies.orient.core.tx.OTransactionIndexChanges;
import com.orientechnologies.orient.core.tx.OTransactionIndexChangesPerKey;
import com.orientechnologies.orient.core.tx.OTransactionInternal;
import com.orientechnologies.orient.distributed.hazelcast.OHazelcastPlugin;
import com.orientechnologies.orient.distributed.impl.coordinator.OSubmitContext;
import com.orientechnologies.orient.distributed.impl.coordinator.OSubmitResponse;
import com.orientechnologies.orient.distributed.impl.coordinator.ddl.ODDLQuerySubmitRequest;
import com.orientechnologies.orient.distributed.impl.coordinator.transaction.*;
import com.orientechnologies.orient.distributed.impl.metadata.ODistributedContext;
import com.orientechnologies.orient.distributed.impl.metadata.OSharedContextDistributed;
import com.orientechnologies.orient.distributed.impl.metadata.OTransactionContext;
import com.orientechnologies.orient.distributed.impl.task.OCopyDatabaseChunkTask;
import com.orientechnologies.orient.distributed.impl.task.ORunQueryExecutionPlanTask;
import com.orientechnologies.orient.distributed.impl.task.OSyncClusterTask;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.*;
import com.orientechnologies.orient.server.distributed.task.ORemoteTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.orientechnologies.orient.core.config.OGlobalConfiguration.DISTRIBUTED_REPLICATION_PROTOCOL_VERSION;

/**
 * Created by tglman on 30/03/17.
 */
public class ODatabaseDocumentDistributed extends ODatabaseDocumentEmbedded {

  private final OHazelcastPlugin distributedManager;

  public ODatabaseDocumentDistributed(OStorage storage, OHazelcastPlugin hazelcastPlugin) {
    super(storage);
    this.distributedManager = hazelcastPlugin;
  }

  /**
   * return the name of local node in the cluster
   *
   * @return the name of local node in the cluster
   */
  public String getLocalNodeName() {
    return distributedManager.getLocalNodeName();
  }

  @Override
  protected void loadMetadata() {
    loadMetadata(this.getSharedContext());
  }

  @Override
  protected void loadMetadata(OSharedContext ctx) {
    metadata = new OMetadataDefault(this);
    sharedContext = ctx;
    metadata.init(sharedContext);
    sharedContext.load(this);
  }

  @Override
  public boolean isSharded() {
    Map<String, Set<String>> clusterMap = getActiveClusterMap();
    Iterator<Set<String>> iter = clusterMap.values().iterator();
    Set<String> firstClusterSet = null;
    if (iter.hasNext()) {
      firstClusterSet = iter.next();
    }
    while (iter.hasNext()) {
      if (!firstClusterSet.equals(iter.next())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isDistributed() {
    return true;
  }

  @Override
  public ODatabaseDocumentInternal copy() {
    ODatabaseDocumentDistributed database = new ODatabaseDocumentDistributed(getStorage(), distributedManager);
    database.init(getConfig(), getSharedContext());
    String user;
    if (getUser() != null) {
      user = getUser().getName();
    } else {
      user = null;
    }
    database.internalOpen(user, null, false);
    database.callOnOpenListeners();
    this.activateOnCurrentThread();
    return database;
  }

  @Override
  public boolean sync(boolean forceDeployment, boolean tryWithDelta) {
    throw new UnsupportedOperationException("not yet implemented");
  }

  @Override
  public Map<String, Object> getHaStatus(boolean servers, boolean db, boolean latency, boolean messages) {
    checkSecurity(ORule.ResourceGeneric.SERVER, "status", ORole.PERMISSION_READ);

    if (distributedManager == null || !distributedManager.isEnabled())
      throw new OCommandExecutionException("OrientDB is not started in distributed mode");

    final String databaseName = getName();

    final ODistributedConfiguration cfg = distributedManager.getDatabaseConfiguration(databaseName);

    Map<String, Object> row = new HashMap<>();
    final StringBuilder output = new StringBuilder();
    if (servers)
      row.put("servers", distributedManager.getClusterConfiguration());
    if (db)
      row.put("database", cfg.getDocument());
    if (latency)
      row.put("latency", ODistributedOutput.formatLatency(distributedManager, distributedManager.getClusterConfiguration()));
    if (messages)
      row.put("messages", ODistributedOutput.formatMessages(distributedManager, distributedManager.getClusterConfiguration()));

    return row;
  }

  @Override
  public boolean removeHaServer(String serverName) {
    checkSecurity(ORule.ResourceGeneric.SERVER, "remove", ORole.PERMISSION_EXECUTE);

    if (distributedManager == null || !distributedManager.isEnabled())
      throw new OCommandExecutionException("OrientDB is not started in distributed mode");

    final String databaseName = getName();

    // The last parameter (true) indicates to set the node's database status to OFFLINE.
    // If this is changed to false, the node will be set to NOT_AVAILABLE, and then the auto-repairer will
    // re-synchronize the database on the node, and then set it to ONLINE.
    return distributedManager.removeNodeFromConfiguration(serverName, databaseName, false, true);
  }

  @Override
  public Map<String, Object> syncCluster(String clusterName) {
    checkSecurity(ORule.ResourceGeneric.CLUSTER, "sync", ORole.PERMISSION_UPDATE);

    if (distributedManager == null || !distributedManager.isEnabled())
      throw new OCommandExecutionException("OrientDB is not started in distributed mode");

    final String databaseName = getName();

    final ODistributedConfiguration cfg = distributedManager.getDatabaseConfiguration(databaseName);
    OServer serverInstance = distributedManager.getServerInstance();
    final String dbPath = serverInstance.getDatabaseDirectory() + databaseName;

    final String nodeName = distributedManager.getLocalNodeName();

    final List<String> nodesWhereClusterIsCfg = cfg.getServers(clusterName, null);
    nodesWhereClusterIsCfg.remove(nodeName);

    if (nodesWhereClusterIsCfg.isEmpty())
      throw new OCommandExecutionException(
          "Cannot synchronize cluster '" + clusterName + "' because is not configured on any running nodes");

    final OSyncClusterTask task = new OSyncClusterTask(clusterName);
    final ODistributedResponse response = distributedManager
        .sendRequest(databaseName, null, nodesWhereClusterIsCfg, task, distributedManager.getNextMessageIdCounter(),
            ODistributedRequest.EXECUTION_MODE.RESPONSE, null, null, null);

    final Map<String, Object> results = (Map<String, Object>) response.getPayload();

    File tempFile = null;
    FileOutputStream out = null;
    try {
      tempFile = new File(Orient.getTempPath() + "/backup_" + databaseName + "_" + clusterName + "_toInstall.zip");
      if (tempFile.exists())
        tempFile.delete();
      else
        tempFile.getParentFile().mkdirs();
      tempFile.createNewFile();

      long fileSize = 0;
      out = new FileOutputStream(tempFile, false);
      for (Map.Entry<String, Object> r : results.entrySet()) {
        final Object value = r.getValue();

        if (value instanceof Boolean) {
          continue;
        } else if (value instanceof Throwable) {
          ODistributedServerLog
              .error(null, nodeName, r.getKey(), ODistributedServerLog.DIRECTION.IN, "error on installing cluster %s in %s",
                  (Exception) value, databaseName, dbPath);
        } else if (value instanceof ODistributedDatabaseChunk) {
          ODistributedDatabaseChunk chunk = (ODistributedDatabaseChunk) value;

          // DELETE ANY PREVIOUS .COMPLETED FILE
          final File completedFile = new File(tempFile.getAbsolutePath() + ".completed");
          if (completedFile.exists())
            completedFile.delete();

          fileSize = writeDatabaseChunk(nodeName, 1, chunk, out);
          for (int chunkNum = 2; !chunk.last; chunkNum++) {
            final Object result = distributedManager.sendRequest(databaseName, null, OMultiValue.getSingletonList(r.getKey()),
                new OCopyDatabaseChunkTask(chunk.filePath, chunkNum, chunk.offset + chunk.buffer.length, false),
                distributedManager.getNextMessageIdCounter(), ODistributedRequest.EXECUTION_MODE.RESPONSE, null, null, null);

            if (result instanceof Boolean)
              continue;
            else if (result instanceof Exception) {
              ODistributedServerLog.error(null, nodeName, r.getKey(), ODistributedServerLog.DIRECTION.IN,
                  "error on installing database %s in %s (chunk #%d)", (Exception) result, databaseName, dbPath, chunkNum);
            } else if (result instanceof ODistributedDatabaseChunk) {
              chunk = (ODistributedDatabaseChunk) result;

              fileSize += writeDatabaseChunk(nodeName, chunkNum, chunk, out);
            }
          }

          out.flush();

          // CREATE THE .COMPLETED FILE TO SIGNAL EOF
          new File(tempFile.getAbsolutePath() + ".completed").createNewFile();
        }
      }

      final String tempDirectoryPath = Orient.getTempPath() + "/backup_" + databaseName + "_" + clusterName + "_toInstall";
      final File tempDirectory = new File(tempDirectoryPath);
      tempDirectory.mkdirs();

      OZIPCompressionUtil.uncompressDirectory(new FileInputStream(tempFile), tempDirectory.getAbsolutePath(), null);

      ODatabaseDocumentInternal db = ODatabaseRecordThreadLocal.instance().getIfDefined();
      final boolean openDatabaseHere = db == null;
      if (db == null)
        db = serverInstance.openDatabase("plocal:" + dbPath, "", "", null, true);

      try {

        final OAbstractPaginatedStorage stg = (OAbstractPaginatedStorage) db.getStorage().getUnderlying();

        // TODO: FREEZE COULD IT NOT NEEDED
        stg.freeze(false);
        try {

          final OPaginatedCluster cluster = (OPaginatedCluster) stg.getClusterByName(clusterName);

          final File tempClusterFile = new File(tempDirectoryPath + "/" + clusterName + OPaginatedCluster.DEF_EXTENSION);

          cluster.replaceFile(tempClusterFile);

        } finally {
          stg.release();
        }

        db.getLocalCache().invalidate();

      } finally {
        if (openDatabaseHere)
          db.close();
      }

      Map<String, Object> result = new HashMap<>();
      result.put("fileSize", fileSize);
      result.put("message", "Cluster correctly replaced");
      result.put("result", true);
      return result;

    } catch (Exception e) {
      ODistributedServerLog
          .error(null, nodeName, null, ODistributedServerLog.DIRECTION.NONE, "error on transferring database '%s' to '%s'", e,
              databaseName, tempFile);
      throw OException.wrapException(new ODistributedException("Error on transferring database"), e);
    } finally {
      try {
        if (out != null) {
          out.flush();
          out.close();
        }
      } catch (IOException e) {
      }
    }

  }

  protected static long writeDatabaseChunk(final String iNodeName, final int iChunkId, final ODistributedDatabaseChunk chunk,
      final FileOutputStream out) throws IOException {

    ODistributedServerLog
        .warn(null, iNodeName, null, ODistributedServerLog.DIRECTION.NONE, "- writing chunk #%d offset=%d size=%s", iChunkId,
            chunk.offset, OFileUtils.getSizeAsString(chunk.buffer.length));
    out.write(chunk.buffer);

    return chunk.buffer.length;
  }

  @Override
  public OResultSet queryOnNode(String nodeName, OExecutionPlan executionPlan, Map<Object, Object> inputParameters) {
    ORunQueryExecutionPlanTask task = new ORunQueryExecutionPlanTask(executionPlan, inputParameters, nodeName);
    ODistributedResponse result = executeTaskOnNode(task, nodeName);
    return task.getResult(result, this);
  }

  public ODistributedResponse executeTaskOnNode(ORemoteTask task, String nodeName) {

    if (distributedManager == null || !distributedManager.isEnabled())
      throw new ODistributedException("OrientDB is not started in distributed mode");

    final String databaseName = getName();

    return distributedManager
        .sendRequest(databaseName, null, Collections.singletonList(nodeName), task, distributedManager.getNextMessageIdCounter(),
            ODistributedRequest.EXECUTION_MODE.RESPONSE, null, null, null);
  }

  @Override
  public void init(OrientDBConfig config, OSharedContext sharedContext) {
    OScenarioThreadLocal.executeAsDistributed(() -> {
      super.init(config, sharedContext);
      return null;
    });
  }

  protected void createMetadata(OSharedContext ctx) {
    // CREATE THE DEFAULT SCHEMA WITH DEFAULT USER
    OSharedContext shared = ctx;
    metadata.init(shared);
    ((OSharedContextDistributed) shared).create(this);
  }

  @Override
  public void internalCommit(OTransactionInternal iTx) {
    int protocolVersion = DISTRIBUTED_REPLICATION_PROTOCOL_VERSION.getValueAsInteger();
    if (OScenarioThreadLocal.INSTANCE.isRunModeDistributed() || (iTx.isSequenceTransaction() && protocolVersion == 2)) {
      //Exclusive for handling schema manipulation, remove after refactor for distributed schema
      super.internalCommit(iTx);
    } else {
      switch (protocolVersion) {
      case 1:
        break;
      case 2:
        distributedCommitV2(iTx);
        break;
      default:
        throw new IllegalStateException(
            "Invalid distributed replicaiton protocol version: " + DISTRIBUTED_REPLICATION_PROTOCOL_VERSION.getValueAsInteger());
      }
    }
  }

  @Override
  public <T> T sendSequenceAction(OSequenceAction action) throws ExecutionException, InterruptedException {
    ODistributedDatabaseImpl sharedDistributeDb = (ODistributedDatabaseImpl) distributedManager.getMessageService()
        .getDatabase(getName());
    OSubmitContext submitContext = ((OSharedContextDistributed) getSharedContext()).getDistributedContext().getSubmitContext();
    sharedDistributeDb.waitForOnline();
    OSessionOperationId id = new OSessionOperationId();
    id.init();
    OSequenceActionCoordinatorSubmit submitAction = new OSequenceActionCoordinatorSubmit(action,
        distributedManager.getLocalNodeName());
    Future<OSubmitResponse> future = submitContext.send(id, submitAction);
    try {
      OSequenceActionCoordinatorResponse response = (OSequenceActionCoordinatorResponse) future.get();
      if (!response.isSuccess()) {
        if (response.getFailedOn().size() > 0) {
          throw new ODatabaseException("Sequence action failed on: " + response.getFailedOn() + " nodes");
        }
      }
      if (response.getLimitReachedOn().size() > 0) {
        if (response.getLimitReachedOn().size() == response.getNumberOfNodesInvolved()) {
          throw new OSequenceLimitReachedException("Sequence limit reached on: " + response.getLimitReachedOn() + " nodes");
        } else {
          throw new ODatabaseException("Inconsistent sequence limit reached on: " + response.getLimitReachedOn() + " nodes");
        }
      }

      Object a = response.getResultOfSenderNode();
      return (T) a;
    } catch (InterruptedException | ExecutionException e) {
      throw e;
    } catch (ODatabaseException exc) {
      //TODO think about rollback
      throw exc;
    }
  }

  private void distributedCommitV2(OTransactionInternal iTx) {
    OTransactionSubmit ts = new OTransactionSubmit(iTx.getRecordOperations(),
        OTransactionSubmit.genIndexes(iTx.getIndexOperations(), iTx), iTx.isUseDeltas());
    if (ts.isEmpty()) {
      return;
    }

    OSubmitContext submitContext = ((OSharedContextDistributed) getSharedContext()).getDistributedContext().getSubmitContext();
    OSessionOperationId id = new OSessionOperationId();
    id.init();

    Future<OSubmitResponse> future = submitContext.send(id, ts);
    try {
      OTransactionResponse response = (OTransactionResponse) future.get();
      if (!response.isSuccess()) {
        throw new ODatabaseException("failed");
      }
      for (OCreatedRecordResponse created : response.getCreatedRecords()) {
        iTx.updateIdentityAfterCommit(created.getCurrentRid(), created.getCreatedRid());
        ORecordOperation rop = iTx.getRecordEntry(created.getCurrentRid());
        if (rop != null) {
          if (created.getVersion() > rop.getRecord().getVersion() + 1)
            // IN CASE OF REMOTE CONFLICT STRATEGY FORCE UNLOAD DUE TO INVALID CONTENT
            rop.getRecord().unload();
          ORecordInternal.setVersion(rop.getRecord(), created.getVersion());
        }
      }
      for (OUpdatedRecordResponse updated : response.getUpdatedRecords()) {
        ORecordOperation rop = iTx.getRecordEntry(updated.getRid());
        if (rop != null) {
          if (updated.getVersion() > rop.getRecord().getVersion() + 1)
            // IN CASE OF REMOTE CONFLICT STRATEGY FORCE UNLOAD DUE TO INVALID CONTENT
            rop.getRecord().unload();
          ORecordInternal.setVersion(rop.getRecord(), updated.getVersion());
        }
      }
      // SET ALL THE RECORDS AS UNDIRTY
      for (ORecordOperation txEntry : iTx.getRecordOperations())
        ORecordInternal.unsetDirty(txEntry.getRecord());

      // UPDATE THE CACHE ONLY IF THE ITERATOR ALLOWS IT.
      OTransactionAbstract.updateCacheFromEntries(iTx.getDatabase(), iTx.getRecordOperations(), true);

    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      e.printStackTrace();
    }
  }

  public void txFirstPhase(OSessionOperationId operationId, List<ORecordOperationRequest> operations,
      List<OIndexOperationRequest> indexes, boolean useDeltas) {
    OTransactionOptimisticDistributed tx = new OTransactionOptimisticDistributed(this, new ArrayList<>(), useDeltas);
    OSharedContextDistributed sharedContext = (OSharedContextDistributed) getSharedContext();
    sharedContext.getDistributedContext().registerTransaction(operationId, tx);
    tx.begin(operations, indexes);
    firstPhaseDataChecks(false, tx);
  }

  public OTransactionOptimisticDistributed txSecondPhase(OSessionOperationId operationId, boolean success) {
    OSharedContextDistributed sharedContext = (OSharedContextDistributed) getSharedContext();
    OTransactionContext context = sharedContext.getDistributedContext().getTransaction(operationId);
    try {
      if (success) {
        OTransactionInternal tx = context.getTransaction();
        tx.setDatabase(this);
        ((OAbstractPaginatedStorage) this.getStorage().getUnderlying()).commitPreAllocated(tx);
        return (OTransactionOptimisticDistributed) tx;
      } else {
        //FOR NOW ROLLBACK DO NOTHING ON THE STORAGE ONLY THE CLOSE IS NEEDED
      }
    } catch (OLowDiskSpaceException ex) {
      distributedManager.setDatabaseStatus(getLocalNodeName(), getName(), ODistributedServerManager.DB_STATUS.OFFLINE);
      throw ex;
    } finally {
      sharedContext.getDistributedContext().closeTransaction(operationId);
    }
    return null;
  }

  public void internalCommit2pc(ONewDistributedTxContextImpl txContext) {
    try {
      OTransactionInternal tx = txContext.getTransaction();
      ((OAbstractPaginatedStorage) this.getStorage().getUnderlying()).commitPreAllocated(tx);
    } catch (OLowDiskSpaceException ex) {
      distributedManager.setDatabaseStatus(getLocalNodeName(), getName(), ODistributedServerManager.DB_STATUS.OFFLINE);
      throw ex;
    } finally {
      txContext.destroy();
    }

  }
  private void firstPhaseDataChecks(boolean local, OTransactionInternal transaction) {
    if (!local) {
      ((OAbstractPaginatedStorage) getStorage().getUnderlying()).preallocateRids(transaction);
    }

    for (Map.Entry<String, OTransactionIndexChanges> change : transaction.getIndexOperations().entrySet()) {
      OIndex<?> index = getSharedContext().getIndexManager().getRawIndex(change.getKey());
      if (OClass.INDEX_TYPE.UNIQUE.name().equals(index.getType()) || OClass.INDEX_TYPE.UNIQUE_HASH_INDEX.name()
          .equals(index.getType())) {
        if (!change.getValue().nullKeyChanges.entries.isEmpty()) {
          OIdentifiable old = (OIdentifiable) index.get(null);
          Object newValue = change.getValue().nullKeyChanges.entries.get(change.getValue().nullKeyChanges.entries.size() - 1).value;
          if (old != null && !old.equals(newValue)) {
            throw new ORecordDuplicatedException(String
                .format("Cannot index record %s: found duplicated key '%s' in index '%s' previously assigned to the record %s",
                    newValue, null, getName(), old.getIdentity()), getName(), old.getIdentity(), null);
          }
        }

        for (OTransactionIndexChangesPerKey changesPerKey : change.getValue().changesPerKey.values()) {
          OIdentifiable old = (OIdentifiable) index.get(changesPerKey.key);
          if (!changesPerKey.entries.isEmpty()) {
            Object newValue = changesPerKey.entries.get(changesPerKey.entries.size() - 1).value;
            if (old != null && !old.equals(newValue)) {
              throw new ORecordDuplicatedException(String
                  .format("Cannot index record %s: found duplicated key '%s' in index '%s' previously assigned to the record %s",
                      newValue, changesPerKey.key, getName(), old.getIdentity()), getName(), old.getIdentity(), changesPerKey.key);
            }
          }
        }
      }
    }

    for (ORecordOperation entry : transaction.getRecordOperations()) {
      if (entry.getType() != ORecordOperation.CREATED) {
        int changeVersion = entry.getRecord().getVersion();
        ORecordMetadata metadata = getStorage().getRecordMetadata(entry.getRID());
        if (metadata == null) {
          if (((OAbstractPaginatedStorage) getStorage().getUnderlying()).isDeleted(entry.getRID())) {
            throw new OConcurrentModificationException(entry.getRID(), changeVersion, changeVersion, entry.getType());
          } else {
            //don't exist i get -1, -1 rid that put the operation in queue for retry.
            throw new OConcurrentCreateException(new ORecordId(-1, -1), entry.getRID());
          }
        }
        int persistentVersion = metadata.getVersion();
        if (changeVersion != persistentVersion) {
          throw new OConcurrentModificationException(entry.getRID(), persistentVersion, changeVersion, entry.getType());
        }
      }
    }
  }

  public void afterCreateOperations(final OIdentifiable id) {
    if (id instanceof ODocument) {
      ODocument doc = (ODocument) id;
      OImmutableClass clazz = ODocumentInternal.getImmutableSchemaClass(this, doc);
      if (clazz != null) {
        OClassIndexManager.checkIndexesAfterCreate(doc, this);
        if (clazz.isFunction()) {
          this.getSharedContext().getFunctionLibrary().createdFunction(doc);
          Orient.instance().getScriptManager().close(this.getName());
        }
        if (clazz.isOuser() || clazz.isOrole()) {
          getMetadata().getSecurity().incrementVersion();
        }
        if (clazz.isSequence()) {
          ((OSequenceLibraryProxy) getMetadata().getSequenceLibrary()).getDelegate().onSequenceCreated(this, doc);
        }
        if (clazz.isScheduler()) {
          getMetadata().getScheduler().scheduleEvent(new OScheduledEvent(doc));
        }
        if (clazz.isTriggered()) {
          OClassTrigger.onRecordAfterCreate(doc, this);
        }
        OLiveQueryHook.addOp(doc, ORecordOperation.CREATED, this);
        OLiveQueryHookV2.addOp(doc, ORecordOperation.CREATED, this);
      }
    }
    callbackHooks(ORecordHook.TYPE.AFTER_CREATE, id);
  }

  public void afterUpdateOperations(final OIdentifiable id) {
    if (id instanceof ODocument) {
      ODocument doc = (ODocument) id;
      OImmutableClass clazz = ODocumentInternal.getImmutableSchemaClass(this, doc);
      if (clazz != null) {
        OClassIndexManager.checkIndexesAfterUpdate((ODocument) id, this);
        if (clazz.isFunction()) {
          this.getSharedContext().getFunctionLibrary().updatedFunction(doc);
          Orient.instance().getScriptManager().close(this.getName());
        }
        if (clazz.isOuser() || clazz.isOrole()) {
          getMetadata().getSecurity().incrementVersion();
        }
        if (clazz.isSequence()) {
          ((OSequenceLibraryProxy) getMetadata().getSequenceLibrary()).getDelegate().onSequenceUpdated(this, doc);
        }
        if (clazz.isTriggered()) {
          OClassTrigger.onRecordAfterUpdate(doc, this);
        }
        OLiveQueryHook.addOp(doc, ORecordOperation.UPDATED, this);
        OLiveQueryHookV2.addOp(doc, ORecordOperation.UPDATED, this);
      }
    }
    callbackHooks(ORecordHook.TYPE.AFTER_UPDATE, id);
  }

  public void afterDeleteOperations(final OIdentifiable id) {
    if (id instanceof ODocument) {
      ODocument doc = (ODocument) id;
      OImmutableClass clazz = ODocumentInternal.getImmutableSchemaClass(this, doc);
      if (clazz != null) {
        OClassIndexManager.checkIndexesAfterDelete(doc, this);
        if (clazz.isFunction()) {
          this.getSharedContext().getFunctionLibrary().droppedFunction(doc);
          Orient.instance().getScriptManager().close(this.getName());
        }
        if (clazz.isOuser() || clazz.isOrole()) {
          getMetadata().getSecurity().incrementVersion();
        }
        if (clazz.isSequence()) {
          ((OSequenceLibraryProxy) getMetadata().getSequenceLibrary()).getDelegate().onSequenceDropped(this, doc);
        }
        if (clazz.isScheduler()) {
          final String eventName = doc.field(OScheduledEvent.PROP_NAME);
          getSharedContext().getScheduler().removeEventInternal(eventName);
        }
        if (clazz.isTriggered()) {
          OClassTrigger.onRecordAfterDelete(doc, this);
        }
      }
      OLiveQueryHook.addOp(doc, ORecordOperation.DELETED, this);
      OLiveQueryHookV2.addOp(doc, ORecordOperation.DELETED, this);
    }
    callbackHooks(ORecordHook.TYPE.AFTER_DELETE, id);
  }

  @Override
  public OView getViewFromCluster(int cluster) {
    OImmutableSchema schema = getMetadata().getImmutableSchemaSnapshot();
    OView view = schema.getViewByClusterId(cluster);
    if (view == null) {
      String viewName = ((OSharedContextDistributed) getSharedContext()).getViewManager().getViewFromOldCluster(cluster);
      if (viewName != null) {
        view = schema.getView(viewName);
      }
    }
    return view;
  }

  public OEnterpriseEndpoint getEnterpriseEndpoint() {
    OServer server = distributedManager.getServerInstance();
    return server.getPlugins().stream().filter(OEnterpriseEndpoint.class::isInstance).findFirst()
        .map(OEnterpriseEndpoint.class::cast).orElse(null);
  }

  @Override
  public int addCluster(String iClusterName, Object... iParameters) {
    if (isRunLocal()) {
      final StringBuilder cmd = new StringBuilder("create cluster `");
      cmd.append(iClusterName);
      cmd.append("`");
      sendDDLCommand(cmd.toString());
      return getClusterIdByName(iClusterName);
    } else {
      return super.addCluster(iClusterName, iParameters);
    }
  }

  @Override
  public int addCluster(String iClusterName, int iRequestedId, Object... iParameters) {
    if (isRunLocal()) {
      final StringBuilder cmd = new StringBuilder("create cluster `");
      cmd.append(iClusterName);
      cmd.append("`");
      cmd.append(" ID ");
      cmd.append(iRequestedId);
      sendDDLCommand(cmd.toString());
      return iRequestedId;
    } else {
      return super.addCluster(iClusterName, iRequestedId, iParameters);
    }
  }

  @Override
  public boolean dropCluster(String iClusterName, boolean iTruncate) {
    if (isRunLocal()) {
      final StringBuilder cmd = new StringBuilder();
      if (iTruncate) {
        cmd.append("truncate cluster `");
      } else {
        cmd.append("create cluster `");
      }
      cmd.append(iClusterName);
      cmd.append("`");
      sendDDLCommand(cmd.toString());
      return true;
    } else {
      return super.dropCluster(iClusterName, iTruncate);
    }
  }

  @Override
  public boolean dropCluster(int iClusterId, boolean iTruncate) {
    if (isRunLocal()) {
      final StringBuilder cmd = new StringBuilder();
      if (iTruncate) {
        cmd.append("truncate cluster ");
      } else {
        cmd.append("create cluster ");
      }
      cmd.append(iClusterId);
      sendDDLCommand(cmd.toString());
      return true;
    } else {
      return super.dropCluster(iClusterId, iTruncate);
    }
  }

  private boolean isDistributeVersionTwo() {
    return getConfiguration().getValueAsInteger(DISTRIBUTED_REPLICATION_PROTOCOL_VERSION) == 2;
  }

  protected boolean isRunLocal() {
    return isDistributeVersionTwo() && getStorage() instanceof OAutoshardedStorage && !((OAutoshardedStorage) getStorage())
        .isLocalEnv();

  }

  public void sendDDLCommand(String command) {
    ODistributedContext distributed = ((OSharedContextDistributed) getSharedContext()).getDistributedContext();
    Future<OSubmitResponse> response = distributed.getSubmitContext()
        .send(new OSessionOperationId(), new ODDLQuerySubmitRequest(command));
    try {
      response.get();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      e.printStackTrace();
    }
  }

  public ODistributedServerManager getDistributedManager() {
    return distributedManager;
  }

}
