package ee.cone.c4proto

import com.squareup.wire.{FieldEncoding, ProtoAdapter, ProtoReader, ProtoWriter}

case class UniversalNode(props: List[UniversalProp])
sealed trait UniversalProp {
  def tag: Int
  def value: Object
  def encodedValue: Array[Byte]
  def encodedSize: Int
  def encode(writer: ProtoWriter): Unit
}

case class UniversalPropImpl[T<:Object](tag: Int, value: T)(adapter: ProtoAdapter[T]) extends UniversalProp {
  def encodedSize: Int = adapter.encodedSizeWithTag(tag, value)
  def encode(writer: ProtoWriter): Unit = adapter.encodeWithTag(writer, tag, value)
  def encodedValue: Array[Byte] = adapter.encode(value)
}

object UniversalProtoAdapter extends ProtoAdapter[UniversalNode](FieldEncoding.LENGTH_DELIMITED, classOf[UniversalNode]) {
  def encodedSize(value: UniversalNode): Int =
    value.props.map(_.encodedSize).sum
  def encode(writer: ProtoWriter, value: UniversalNode): Unit =
    value.props.foreach(_.encode(writer))
  def decode(reader: ProtoReader): UniversalNode = throw new Exception("not implemented")
}

class IndentedParser(
  splitter: Char, lineSplitter: String,
  propTypeRegistry: String⇒StringToUniversalProp.Converter
) {
  def parse(data: okio.ByteString): UniversalNode = {
    val lines = data.utf8().split(lineSplitter).filter(_.nonEmpty).toList
    UniversalNode(parseProps(lines, Nil))
  }
  //@tailrec final
  private def parseProp(key: String, value: List[String]): UniversalProp = {
    val Array(xHex,handlerName) = key.split(splitter)
    val ("0x", hex) = xHex.splitAt(2)
    val tag = Integer.parseInt(hex, 16)
    if(handlerName != "Node") propTypeRegistry(handlerName)(tag,value.mkString(lineSplitter))
    else UniversalPropImpl(tag,UniversalNode(parseProps(value, Nil)))(UniversalProtoAdapter)
  }
  private def parseProps(lines: List[String], res: List[UniversalProp]): List[UniversalProp] =
    if(lines.isEmpty) res.reverse else {
      val key = lines.head
      val value = lines.tail.takeWhile(_.head == splitter).map(_.tail)
      val left = lines.tail.drop(value.size)
      parseProps(left, parseProp(key, value) :: res)
    }
}

object StringToUniversalProp {
  type Converter = (Int,String)⇒UniversalProp
}

object StringToUniversalPropImpl {
  def string(tag: Int, value: String): UniversalProp =
    UniversalPropImpl[String](tag,value)(ProtoAdapter.STRING)
  def number(tag: Int, value: String): UniversalProp = {
    val BigDecimalFactory(scale,bytes) = BigDecimal(value)
    val scaleProp = UniversalPropImpl(0x0001,scale:Integer)(ProtoAdapter.SINT32)
    val bytesProp = UniversalPropImpl(0x0002,bytes)(ProtoAdapter.BYTES)
    UniversalPropImpl(tag,UniversalNode(List(scaleProp,bytesProp)))(UniversalProtoAdapter)
  }
  def converters: List[(String,StringToUniversalProp.Converter)] =
    ("String", string _) :: ("BigDecimal", number _) :: Nil
}

//protobuf universal draft