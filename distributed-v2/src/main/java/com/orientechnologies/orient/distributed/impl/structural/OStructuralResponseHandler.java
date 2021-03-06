package com.orientechnologies.orient.distributed.impl.structural;

public interface OStructuralResponseHandler {
  boolean receive(OStructuralCoordinator coordinator, OStructuralRequestContext context, OStructuralDistributedMember member,
      OStructuralNodeResponse response);

  boolean timeout(OStructuralCoordinator coordinator, OStructuralRequestContext context);
}
