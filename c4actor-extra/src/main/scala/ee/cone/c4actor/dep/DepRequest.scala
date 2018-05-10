package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.DepTypeContainer.DepRequest
import ee.cone.c4actor.Types.SrcId

case class DepInnerRequest(srcId: SrcId, request: DepRequest) //TODO Store serialized version
case class DepOuterRequest(srcId: SrcId, innerRequest: DepInnerRequest, parentSrcId: SrcId)

case class UnresolvedDep(rq: DepOuterRequest, resolvable: DepResolvable)
