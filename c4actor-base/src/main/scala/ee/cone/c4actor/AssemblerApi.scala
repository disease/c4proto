
package ee.cone.c4actor

import Types._
import ee.cone.c4actor.TreeAssemblerTypes.Replace

import scala.collection.immutable.Map

object Single {
  def apply[C](l: List[C]): C = if(l.tail.nonEmpty) throw new Exception else l.head
  def option[C](l: List[C]): Option[C] = if(l.isEmpty) None else Option(apply(l))
  def list[C](l: List[C]): List[C] = if(l.isEmpty || l.tail.isEmpty) l else throw new Exception
}

trait IndexFactory {
  def createJoinMapIndex[R<:Object,TK,RK](join: Join[R,TK,RK]):
    WorldPartExpression
      with DataDependencyFrom[Index[TK, Object]]
      with DataDependencyTo[Index[RK, R]]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[WorldKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: WorldKey[To]
}

trait Join[Result,JoinKey,MapKey]
  extends DataDependencyFrom[Index[JoinKey,Object]]
  with DataDependencyTo[Index[MapKey,Result]]
{
  def joins(in: Seq[Values[Object]]): Iterable[(MapKey,Result)]
  def sort(values: Iterable[Result]): List[Result]
}

////
// moment -> mod/index -> key/srcId -> value -> count

trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: World,
  diff: Map[WorldKey[_],Map[Object,Boolean]],
  current: World
)

class OriginalWorldPart[A<:Object](val outputWorldKey: WorldKey[A]) extends DataDependencyTo[A]

object TreeAssemblerTypes {
  type Replace = Map[WorldKey[_],Index[Object,Object]] ⇒ World ⇒ World
}

trait TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace
}
