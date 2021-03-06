package ee.cone.c4actor.dep_impl

import scala.collection.immutable.{Map, Seq}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest, GroupId}
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.ToByteString

case class DepResponseImpl(innerRequest: DepInnerRequest, valueHashed: PreHashed[Option[_]]) extends DepResponse {
  def value: Option[_] = valueHashed.value
}
case class DepInnerResolvable(result: DepResponse, subRequests: Seq[(SrcId,DepOuterRequest)])

@assemble class DepAssemble(reg: DepRequestHandlerRegistry) extends Assemble {

  def resolvableToSubRequests(
    innerId: SrcId,
    @was resolvable: Each[DepInnerResolvable]
  ): Values[(GroupId, DepOuterRequest)] = resolvable.subRequests

  def toSingleInnerRequest(
    innerId: SrcId,
    @by[GroupId] outerRequests: Values[DepOuterRequest]
  ): Values[(SrcId,DepInnerRequest)] =
    Seq(WithPK(Single(outerRequests.map(_.innerRequest).distinct)))

  def resolvableToResponse(
    innerId: SrcId,
    @was resolvable: Each[DepInnerResolvable]
  ): Values[(SrcId, DepResponse)] = List(WithPK(resolvable.result))

  def responseToParent(
    innerId: SrcId,
    response: Each[DepResponse],
    @by[GroupId] outer: Each[DepOuterRequest]
  ): Values[(GroupId, DepResponse)] = List(outer.parentSrcId → response)

  def handle(
    innerId: SrcId,
    req: Each[DepInnerRequest],
    @by[GroupId] responses: Values[DepResponse]
  ): Values[(SrcId, DepInnerResolvable)] = for {
    handle ← reg.handle(req).toList
  } yield WithPK(handle(responses))

  def unresolvedRequestProducer(
    requestId: SrcId,
    rq: Each[DepInnerRequest],
    responses: Values[DepResponse]
  ): Values[(SrcId, DepUnresolvedRequest)] =
    if (responses.forall(_.value.isEmpty))
      List(WithPK(DepUnresolvedRequest(rq.srcId, rq.request, responses.size)))
    else Nil
}

case class DepRequestHandlerRegistry(
  depOuterRequestFactory: DepOuterRequestFactory,
  depResponseFactory: DepResponseFactory,
  handlerSeq: Seq[DepHandler]
)(
  handlers: Map[String,(DepRequest,DepCtx)⇒Resolvable[_]] =
    handlerSeq.collect{ case h: InternalDepHandler ⇒ h }.groupBy(_.className)
      .transform { (k, handlers) ⇒
        val by = Single(handlers.collect { case h: ByDepHandler ⇒ h.handler })
        val adds = handlers.collect { case h: AddDepHandler ⇒ h.handler }
        (req: DepRequest, ctx: DepCtx) ⇒
        //by(req).resolve(adds.foldLeft(ctx)((acc:DepCtx,add)⇒acc ++ (add(req):DepCtx)))
        by(req).resolve(ctx ++ adds.flatMap(_(req)))
      }
) {
  def handle(req: DepInnerRequest): Option[Values[DepResponse]⇒DepInnerResolvable] =
    handlers.get(req.request.getClass.getName).map{ (handle:(DepRequest,DepCtx)⇒Resolvable[_]) ⇒ (responses:Values[DepResponse]) ⇒
      val ctx: DepCtx = (for {
        response ← responses
        value ← response.value
      } yield response.innerRequest.request → value).toMap
      val resolvable: Resolvable[_] = handle(req.request,ctx)
      val response = depResponseFactory.wrap(req,resolvable.value)
      DepInnerResolvable(response, resolvable.requests.distinct.map(depOuterRequestFactory.tupled(req.srcId)))
    }
}

case class DepResponseFactoryImpl()(preHashing: PreHashing) extends DepResponseFactory {
  def wrap(req: DepInnerRequest, value: Option[_]): DepResponse =
    DepResponseImpl(req,preHashing.wrap(value))
}

case class DepOuterRequestFactoryImpl(idGenUtil: IdGenUtil)(qAdapterRegistry: QAdapterRegistry) extends DepOuterRequestFactory {
  def tupled(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest) = {
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = ToByteString(valueAdapter.encode(rq))
    val innerId = idGenUtil.srcIdFromSerialized(valueAdapter.id, bytes)
    val inner = DepInnerRequest(innerId, rq)
    val outerId = idGenUtil.srcIdFromSrcIds(parentId, inner.srcId)
    inner.srcId → DepOuterRequest(outerId, inner, parentId)
  }
}

abstract class InternalDepHandler(val className: String) extends DepHandler
case class ByDepHandler(name: String)(val handler: DepRequest ⇒ Dep[_]) extends InternalDepHandler(name)
case class AddDepHandler(name: String)(val handler: DepRequest ⇒ DepCtx) extends InternalDepHandler(name)
case class DepAskImpl[In<:Product,Out](name: String, depFactory: DepFactory)(val inClass: Class[In]) extends DepAsk[In,Out] {
  def ask: In ⇒ Dep[Out] = request ⇒ depFactory.uncheckedRequestDep[Out](request)
  def by(handler: In ⇒ Dep[Out]): DepHandler = ByDepHandler(name)(handler.asInstanceOf[DepRequest ⇒ Dep[_]])
  def byParent[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ Map[In, Out]): DepHandler =
    AddDepHandler(reason match { case DepAskImpl(nm,_) ⇒ nm })(handler.asInstanceOf[DepRequest ⇒ DepCtx])
}
case class DepAskFactoryImpl(depFactory: DepFactory) extends DepAskFactory {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out] =
    DepAskImpl[In,Out](in.getName, depFactory)(in)
}

//handler(in).resolve(ctx)
// ContextId: ContextIdRequest() →
