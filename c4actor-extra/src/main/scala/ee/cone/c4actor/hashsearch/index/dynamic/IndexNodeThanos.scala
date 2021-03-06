package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.{HashSearchModelsApp, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{IndexNodeSettings, _}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import AnyAdapter._
import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.dep.request.{CurrentTimeConfig, CurrentTimeConfigApp}
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4proto.ToByteString

import scala.collection.immutable
import scala.collection.immutable.Seq

case class ProductWithId[Model <: Product](modelCl: Class[Model], modelId: Int)

trait DynamicIndexModelsApp {
  def dynIndexModels: List[ProductWithId[_ <: Product]] = Nil
}

trait DynamicIndexAssemble
  extends AssemblesApp
    with WithIndexNodeProtocol
    with DynamicIndexModelsApp
    with SerializationUtilsApp
    with CurrentTimeConfigApp
    with HashSearchDynamicIndexApp
    with HashSearchRangerRegistryApp {

  def dynamicIndexRefreshRateSeconds: Long

  def dynamicIndexNodeDefaultSetting: IndexNodeSettings = IndexNodeSettings("", false, None)

  override def currentTimeConfig: List[CurrentTimeConfig] =
    CurrentTimeConfig("DynamicIndexAssembleRefresh", dynamicIndexRefreshRateSeconds) ::
      super.currentTimeConfig

  override def assembles: List[Assemble] = {
    modelListIntegrityCheck(dynIndexModels)
    new ThanosTimeFilters(maxTransforms = dynamicIndexMaxEvents) ::
      dynIndexModels.distinct.map(p ⇒
        new IndexNodeThanos(
          p.modelCl, p.modelId,
          dynamicIndexAssembleDebugMode, qAdapterRegistry, serializer, hashSearchRangerRegistry,
          dynamicIndexRefreshRateSeconds, dynamicIndexAutoStaticNodeCount, dynamicIndexAutoStaticLiveSeconds, dynamicIndexNodeDefaultSetting, dynamicIndexDeleteAnywaySeconds,
          hashSearchRangerRegistry, idGenUtil
        )
      ) :::
      super.assembles
  }

  def dynamicIndexMaxEvents: Int = 100000

  def dynamicIndexAssembleDebugMode: Boolean = false

  def dynamicIndexAutoStaticNodeCount: Int = 1000

  def dynamicIndexAutoStaticLiveSeconds: Long = 60L * 60L

  def dynamicIndexDeleteAnywaySeconds: Long = 60L * 60L * 24L * 1L

  private def modelListIntegrityCheck: List[ProductWithId[_ <: Product]] ⇒ Unit = list ⇒ {
    val map = list.distinct.groupBy(_.modelId)
    if (map.values.forall(_.size == 1)) {
    } else {
      FailWith.apply(s"Dyn model List contains models with same Id: ${map.filter(_._2.size > 1)}")
    }
  }
}

object IndexNodeThanosUtils {
  def getIndexNodeSrcId(ser: SerializationUtils, modelId: Int, byId: Long, lensName: List[String]): SrcId = {
    ser.srcIdFromSrcIds("IndexNode" :: modelId.toString :: byId.toString :: lensName)
  }
}

case class IndexNodeRich[Model <: Product](
  srcId: SrcId,
  keepAllAlive: Boolean,
  indexNode: IndexNode,
  indexByNodes: List[IndexByNodeRich[Model]]
)

case class IndexByNodeRichCount[Model <: Product](
  srcId: SrcId,
  indexByNodeCount: Int
)

case class IndexByNodeRich[Model <: Product](
  srcId: SrcId,
  isAlive: Boolean,
  indexByNode: IndexByNode
)

case class IndexByNodeStats(
  srcId: SrcId,
  lastPongSeconds: Long,
  parentId: SrcId
)

import IndexNodeThanosUtils._

sealed trait ThanosTimeTypes {
  type TimeIndexNodeThanos = All

  type PowerIndexNodeThanos = All

  type ThanosLEventsTransforms = All
}

@assemble class ThanosTimeFilters(refreshRateSeconds: Long = 60, maxTransforms: Int) extends Assemble with ThanosTimeTypes {
  def TimeFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborns: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(TimeIndexNodeThanos, CurrentTimeNode)] =
    (for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleRefresh"
    } yield WithAll(pong)).headOption.to[Values]

  def PowerFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborn: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(PowerIndexNodeThanos, CurrentTimeNode)] =
    (for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleRefresh"
    } yield WithAll(CurrentTimeNode("DynamicIndexAssembleGC", pong.currentTimeSeconds / (refreshRateSeconds * 5) * (refreshRateSeconds * 5)))).headOption.to[Values]


  def ApplyThanosTransforms(
    firsBornId: SrcId,
    firstborn: Values[Firstborn],
    @by[ThanosLEventsTransforms] @distinct events: Values[LEventTransform]
  ): Values[(SrcId, TxTransform)] =
    WithPK(CollectiveTransform("ThanosTX", events.take(maxTransforms))) :: Nil
}

import ee.cone.c4actor.hashsearch.rangers.IndexType._

case class PreparedLeaf[Model <: Product](
  srcId: SrcId,
  byId: Long,
  by: AnyOrig,
  lensName: List[String],
  byOrig: Product
)

trait IndexNodeThanosUtils[Model <: Product] {
  def prepareLeaf(
    prodCondition: ProdCondition[_ <: Product, Model],
    qAdapterRegistry: QAdapterRegistry,
    rangerRegistryApi: HashSearchRangerRegistryApi,
    idGenUtil: IdGenUtil
  ): List[(String, PreparedLeaf[Model])] = {
    qAdapterRegistry.byName.get(prodCondition.by.getClass.getName).map(_.id) match {
      case Some(byId) ⇒
        rangerRegistryApi.getByByIdUntyped(byId) match {
          case Some(ranger) ⇒
            val preparedBy = innerPrepareLeaf(ranger, prodCondition.by)
            val lensName: List[String] = prodCondition.metaList.collect { case a: NameMetaAttr ⇒ a.value }
            val serialized: AnyOrig = AnyAdapter.encode(qAdapterRegistry)(preparedBy)
            val newId: SrcId = idGenUtil.srcIdFromStrings("IndexByNode" :: idGenUtil.srcIdFromSerialized(byId, serialized.value) :: lensName: _*)
            WithPK(PreparedLeaf[Model](newId, byId, serialized, lensName, preparedBy)) :: Nil
          case None ⇒ Nil
        }
      case None ⇒ Nil
    }
  }

  def innerPrepareLeaf[By <: Product](
    ranger: RangerWithCl[By, _],
    by: Product
  ): Product = ranger.prepareRequest(by.asInstanceOf[By])
}

@assemble class IndexNodeThanos[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  debugMode: Boolean,
  qAdapterRegistry: QAdapterRegistry,
  ser: SerializationUtils,
  hashSearchRangerRegistryApi: HashSearchRangerRegistryApi,
  refreshRateSeconds: Long = 60,
  autoCount: Int,
  autoLive: Long,
  dynamicIndexNodeDefaultSetting: IndexNodeSettings,
  deleteAnyway: Long,
  rangerRegistryApi: HashSearchRangerRegistryApi,
  idGenUtil: IdGenUtil
) extends Assemble with ThanosTimeTypes with IndexNodeThanosUtils[Model] {
  type IndexNodeId = SrcId
  type IndexByNodeId = SrcId

  // Condition to Node
  def SoulLeafToIndexNodeId(
    leafId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(IndexNodeId, InnerLeaf[Model])] =
    leaf.condition match {
      case prod: ProdCondition[_, Model] ⇒
        val nameList = prod.metaList.collect { case a: NameMetaAttr ⇒ a.value }
        val byIdOpt = qAdapterRegistry.byName.get(prod.by.getClass.getName).map(_.id)
        if (byIdOpt.isDefined) {
          (getIndexNodeSrcId(ser, modelId, byIdOpt.get, nameList) → leaf) :: Nil
        } else {
          Nil
        }
      case _ ⇒ Nil
    }

  type FilterEmAll = SrcId

  def SpaceLeafToFilterEmAll(
    leafId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(FilterEmAll, PreparedLeaf[Model])] =
    leaf.condition match {
      case prod: ProdCondition[_, Model] ⇒
        prepareLeaf(prod, qAdapterRegistry, rangerRegistryApi, idGenUtil)
      case _ ⇒ Nil
    }

  def SpaceLeafToPreparedNodeId(
    leafId: SrcId,
    @by[FilterEmAll] @distinct leafs: Values[PreparedLeaf[Model]]
  ): Values[(IndexByNodeId, PreparedLeaf[Model])] =
    WithPK(Single(leafs)) :: Nil

  // Node creation
  def SoulIndexNodeCreation(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNode],
    @by[IndexNodeId] leafs: Values[InnerLeaf[Model]]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    (indexNodes.toList.filter(_.modelId == modelId), leafs.toList) match {
      case (Nil, Seq(x, xs@_*)) ⇒
        val prod = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val byIdOpt = qAdapterRegistry.byName.get(prod.by.getClass.getName).map(_.id)
        val nameList = prod.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
        byIdOpt match {
          case Some(byId) =>
            val srcId = getIndexNodeSrcId(ser, modelId, byId, nameList)
            if (debugMode)
              PrintColored("y")(s"[Thanos.Soul, $modelId] Created IndexNode for ${(prod.by.getClass.getName, nameList)},${(modelCl.getName, modelId)}")
            val indexType: IndexType =
              hashSearchRangerRegistryApi
                .getByByIdUntyped(byId).map(_.indexType).getOrElse(Default)
            WithAll(SoulTransform(srcId, modelId, byId, nameList, dynamicIndexNodeDefaultSetting, indexType)) :: Nil
          case None =>
            PrintColored("r")(s"[Thanos.Soul, $modelId] Non serializable condition: $prod")
            Nil
        }
      case (x :: Nil, Seq(y, ys@_*)) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos.Soul, $modelId] Both alive $x ${y.condition}")
        Nil
      case (_ :: Nil, Seq()) ⇒
        Nil
      case (Nil, Seq()) ⇒
        Nil
      case _ ⇒ FailWith.apply("Multiple indexNodes in [Thanos.Soul, $modelId] - SoulIndexNodeCreation")
    }

  // ByNode creation
  def RealityInnerLeafIndexByNode(
    innerLeafId: SrcId,
    @by[IndexByNodeId] innerLeafs: Values[PreparedLeaf[Model]],
    indexByNodes: Values[IndexByNode],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] = {
    (innerLeafs.toList, indexByNodes.toList.filter(_.modelId == modelId)) match {
      case (x :: Nil, Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Created ByNode for ${x.byOrig}")
        val parentId = getIndexNodeSrcId(ser, modelId, x.byId, x.lensName)
        WithAll(RealityTransform(x.srcId, parentId, x.by, modelId, autoLive)) :: Nil
      case (Nil, y :: Nil) ⇒
        if (indexByNodesLastSeen.isEmpty)
          WithAll(MindTransform(y.srcId)) :: Nil
        else
          Nil
      case (x :: Nil, y :: Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Both alive ${x.byOrig} ${decode(qAdapterRegistry)(y.byInstance.get)}")
        if (indexByNodesLastSeen.nonEmpty)
          WithAll(RevertedMindTransform(x.srcId)) :: Nil
        else
          Nil
      case (Nil, Nil) ⇒ Nil
      case _ ⇒ FailWith.apply(s"Multiple inputs in [Thanos.Reality, $modelId] - RealityGiveLifeToIndexByNode")
    }
  }

  // ByNode rich
  def SpaceIndexByNodeRich(
    indexByNodeId: SrcId,
    nodes: Values[IndexByNode],
    @by[IndexByNodeId] innerLeafs: Values[PreparedLeaf[Model]],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen],
    indexByNodeSettings: Values[IndexByNodeSettings],
    @by[PowerIndexNodeThanos] currentTimes: Each[CurrentTimeNode]
  ): Values[(SrcId, IndexByNodeRich[Model])] =
    if (nodes.size == 1) {
      val node = nodes.head
      if (node.modelId == modelId) {
        val currentTime = currentTimes.currentTimeSeconds
        val leafIsPresent = innerLeafs.nonEmpty
        val lastPong = indexByNodesLastSeen.headOption.map(_.lastSeenAtSeconds).getOrElse(0L)
        val setting = indexByNodeSettings.headOption
        val isAlive =
          leafIsPresent || indexByNodesLastSeen.isEmpty ||
            (setting.isDefined && (setting.get.alwaysAlive || currentTime - setting.get.keepAliveSeconds.getOrElse(0L) - lastPong <= 0))
        val rich = IndexByNodeRich[Model](node.srcId, isAlive, node)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexByNodeRich ${(isAlive, currentTime, node.srcId, innerLeafs.headOption.map(_.byOrig))}")
        WithPK(rich) :: Nil
      }
      else Nil
    } else if (innerLeafs.size == 1) {
      val leaf = innerLeafs.head
      val parentId = getIndexNodeSrcId(ser, modelId, leaf.byId, leaf.lensName)
      val stubIndexByNode = IndexByNode(leaf.srcId, parentId, modelId, Option(leaf.by))
      val rich = IndexByNodeRich[Model](leaf.srcId, isAlive = true, stubIndexByNode)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space, $modelId] Created from leaf IndexByNodeRich ${(leaf.srcId, innerLeafs.headOption.map(_.byOrig))}")
      WithPK(rich) :: Nil
    } else Nil

  // ByNodeRich to Node
  def SpaceIndexByNodeRichToIndexNodeId(
    indexByNodeRichId: SrcId,
    indexByNode: Each[IndexByNodeRich[Model]]
  ): Values[(IndexNodeId, IndexByNodeRich[Model])] =
    List(indexByNode.indexByNode.indexNodeId → indexByNode)

  // NodeRich - dynamic
  def SpaceIndexNodeRichNoneAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNode],
    indexNodeSettings: Values[IndexNodeSettings],
    @by[IndexNodeId] indexByNodeRiches: Values[IndexByNodeRich[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] =
    if (indexNode.modelId == modelId) {
      val settings = indexNodeSettings.headOption
      val isAlive = (settings.isDefined && settings.get.allAlwaysAlive) || (settings.isDefined && settings.get.keepAliveSeconds.isEmpty && indexByNodeRiches.size > autoCount)
      if (!isAlive) {
        val rich = IndexNodeRich[Model](indexNode.srcId, isAlive, indexNode, indexByNodeRiches.toList)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich None Alive ${(isAlive, indexNode.srcId, indexByNodeRiches.size)}")
        WithPK(rich) :: Nil
      } else {
        Nil
      }
    }
    else Nil

  // Count children
  def PowerIndexByNodeCounter(
    indexNodeId: SrcId,
    @by[IndexNodeId] indexByNodeRiches: Values[IndexByNodeRich[Model]]
  ): Values[(SrcId, IndexByNodeRichCount[Model])] =
    WithPK(IndexByNodeRichCount[Model](indexNodeId, indexByNodeRiches.size)) :: Nil

  // NodeRich - static
  def SpaceIndexNodeRichAllAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNode],
    indexNodeSettings: Values[IndexNodeSettings],
    childCounts: Values[IndexByNodeRichCount[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] =
    if (indexNode.modelId == modelId) {
      val settings = indexNodeSettings.headOption
      val childCount = childCounts.headOption.map(_.indexByNodeCount).getOrElse(0)
      val isAlive = (settings.isDefined && settings.get.allAlwaysAlive) || (settings.isDefined && settings.get.keepAliveSeconds.isEmpty && childCount > autoCount)
      if (isAlive) {
        val rich = IndexNodeRich[Model](indexNode.srcId, isAlive, indexNode, Nil)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich All alive ${(isAlive, indexNode.srcId)}")
        WithPK(rich) :: Nil
      } else {
        Nil
      }
    }
    else Nil

  // GC Nodes
  def PowerGCIndexByNodes(
    indexNodeRichId: SrcId,
    parent: Each[IndexNodeRich[Model]]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    if (!parent.keepAllAlive)
      for {
        child ← parent.indexByNodes
        if !child.isAlive
      } yield {
        if (debugMode)
          PrintColored("m")(s"[Thanos.Power, $modelId] Deleted ${(child.indexByNode.srcId, decode(qAdapterRegistry)(child.indexByNode.byInstance.get))}")
         WithAll( PowerTransform(child.srcId, s"Power-${child.srcId}"))
      }
    else Nil

  def PowerGCIndexForStatic(
    indexByNodeId: SrcId,
    indexByNodes: Each[IndexByNode],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen],
    @by[PowerIndexNodeThanos] currentTimes: Each[CurrentTimeNode]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    if (indexByNodesLastSeen.nonEmpty && currentTimes.currentTimeSeconds - indexByNodesLastSeen.head.lastSeenAtSeconds > deleteAnyway) {
      WithAll(PowerTransform(indexByNodes.srcId, s"Anyway-${indexByNodes.srcId}")) :: Nil
    } else {
      Nil
    }
}

case class RealityTransform[Model <: Product, By <: Product](srcId: SrcId, parentNodeId: String, byInstance: AnyOrig, modelId: Int, defaultLive: Long) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val parentOpt: Option[IndexNodeSettings] = ByPK(classOf[IndexNodeSettings]).of(local).get(parentNodeId)
    val settings: immutable.Seq[LEvent[IndexByNodeSettings]] = if (parentOpt.isDefined) {
      val IndexNodeSettings(_, keepAlive, aliveSeconds) = parentOpt.get
      val liveFor = aliveSeconds.getOrElse(defaultLive)
      LEvent.update(IndexByNodeSettings(srcId, keepAlive, Some(liveFor)))
    } else Nil
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
    val firstTime = System.currentTimeMillis()
    val timedLocal: Seq[LEvent[Product]] =
      LEvent.update(IndexByNode(srcId, parentNodeId, modelId, Some(byInstance))) ++ settings ++ LEvent.delete(IndexByNodeLastSeen(srcId, nowSeconds))
    val secondTime = System.currentTimeMillis()
    LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))) ++ timedLocal
  }
}

case class SoulTransform(srcId: SrcId, modelId: Int, byAdapterId: Long, lensName: List[String], default: IndexNodeSettings, indexType: IndexType) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val firstTime = System.currentTimeMillis()
    val IndexNodeSettings(_, alive, time) = default
    val aliveWithType = if (indexType == Static) true else alive
    val timedLocal: Seq[LEvent[Product]] =
      LEvent.update(IndexNode(srcId, modelId, byAdapterId, lensName)) ++
        LEvent.update(IndexNodeSettings(srcId, allAlwaysAlive = aliveWithType, keepAliveSeconds = time))
    val secondTime = System.currentTimeMillis()
    LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))) ++ timedLocal
  }
}

case class PowerTransform(srcId: SrcId, extraKey: String) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] =
    LEvent.delete(IndexByNodeLastSeen(srcId, 0L)) ++ LEvent.delete(IndexByNode(srcId, "", 0, None)) ++ LEvent.delete(IndexByNodeSettings(srcId, false, None))
}

case class MindTransform(srcId: SrcId) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
   LEvent.update(IndexByNodeLastSeen(srcId, nowSeconds))
  }
}

case class RevertedMindTransform(srcId: SrcId) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
    LEvent.delete(IndexByNodeLastSeen(srcId, nowSeconds))
  }
}
