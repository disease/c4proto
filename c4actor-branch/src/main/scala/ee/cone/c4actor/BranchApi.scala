
package ee.cone.c4actor

import scala.collection.immutable.Seq
import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto._

object BranchTypes {
  type BranchKey = SrcId
}

trait BranchMessage {
  def header: String⇒String
  def body: okio.ByteString
}

trait BranchHandler extends Product {
  def branchKey: SrcId
  def exchange: BranchMessage ⇒ Context ⇒ Context
  def seeds: Context ⇒ List[BranchResult]
}

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def sessionKeys: Context ⇒ Set[BranchRel]
  type Send = Option[(String,String) ⇒ Context ⇒ Context]
  def sending: Context ⇒ (Send,Send)
  def relocate(to: String): Context ⇒ Context
}

trait MessageFromAlien extends BranchMessage with Product {
  def srcId: String
  def index: Long
  def rm: Context ⇒ Context
}

trait BranchOperations {
  def toSeed(value: Product): BranchResult
  def toRel(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel)
}

case class BranchRel(srcId: SrcId, seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean)

@protocol object BranchProtocol extends Protocol {
  @Id(0x0040) case class BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString,
    @Id(0x0044) children: List[BranchResult],
    @Id(0x0045) position: String
  )

  @Id(0x0046) case class SessionFailure(
    @Id(0x0047) srcId: String,
    @Id(0x0048) text: String,
    @Id(0x0049) time: Long,
    @Id(0x004A) sessionKeys: List[String]
    //retry: List[HttpPost]
  )

}

case object SendToAlienKey extends SharedComponentKey[(Seq[String],String,String)⇒Context⇒Context]

trait BranchError {
  def message: String
}