package ee.cone.c4gate


import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.TcpProtocol._
import ee.cone.c4proto.Protocol

trait TcpServerApp extends ToStartApp with AssemblesApp with InitLocalsApp with ProtocolsApp {
  def config: Config
  def qMessages: QMessages
  def worldProvider: WorldProvider
  def mortal: MortalFactory

  private lazy val tcpPort = config.get("C4TCP_PORT").toInt
  private lazy val tcpServer = new TcpServerImpl(tcpPort, new TcpHandlerImpl(qMessages, worldProvider))
  override def toStart: List[Executable] = tcpServer :: super.toStart
  override def assembles: List[Assemble] =
    mortal(classOf[TcpDisconnect]) :: mortal(classOf[TcpWrite]) ::
    new TcpAssemble :: super.assembles
  override def initLocals: List[InitLocal] = tcpServer :: super.initLocals
  override def protocols: List[Protocol] = TcpProtocol :: super.protocols
}

class TcpHandlerImpl(qMessages: QMessages, worldProvider: WorldProvider) extends TcpHandler {
  private def changeWorld(transform: World ⇒ World): Unit =
    Option(worldProvider.createTx()).map(transform).foreach(qMessages.send)
  override def beforeServerStart(): Unit = changeWorld{ local ⇒
    val world = TxKey.of(local).world
    val connections =  By.srcId(classOf[TcpConnection]).of(world).values.flatten.toList
    LEvent.add(connections.flatMap(LEvent.delete))(local)
  }
  override def afterConnect(key: String, sender: SenderToAgent): Unit =
    changeWorld(LEvent.add(LEvent.update(TcpConnection(key))))
  override def afterDisconnect(key: String): Unit =
    changeWorld(LEvent.add(LEvent.delete(TcpConnection(key))))
}

case class TcpConnectionTxTransform(
    connectionKey: SrcId,
    tcpDisconnects: Values[TcpDisconnect],
    writes: Values[TcpWrite]
) extends TxTransform {
  def transform(local: World): World = {
    def sender = GetSenderKey.of(local)(connectionKey)
    for(d ← tcpDisconnects; s ← sender) s.close()
    for(message ← writes; s ← sender) s.add(message.body.toByteArray)
    LEvent.add(writes.flatMap(LEvent.delete))(local)
  }
}

@assemble class TcpAssemble extends Assemble {
  type ConnectionKey = SrcId

  def joinTcpWrite(key: SrcId, writes: Values[TcpWrite]): Values[(ConnectionKey, TcpWrite)] =
    writes.map(write⇒write.connectionKey→write)

  def joinTxTransform(
      key: SrcId,
      tcpConnections: Values[TcpConnection],
      tcpDisconnects: Values[TcpDisconnect],
      @by[ConnectionKey] writes: Values[TcpWrite]
  ): Values[(SrcId,TxTransform)] =
    for(c ← tcpConnections)
      yield WithSrcId(TcpConnectionTxTransform(c.connectionKey, tcpDisconnects, writes.sortBy(_.priority)))

  def lifeOfConnectionToDisconnects(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    tcpDisconnects: Values[TcpDisconnect]
  ): Values[(Alive,TcpDisconnect)] =
    for(d ← tcpDisconnects if tcpConnections.nonEmpty) yield WithSrcId(d)

  def lifeOfConnectionToTcpWrites(
    key: SrcId,
    tcpConnections: Values[TcpConnection],
    @by[ConnectionKey] writes: Values[TcpWrite]
  ): Values[(Alive,TcpWrite)] =
    for(d ← writes if tcpConnections.nonEmpty) yield WithSrcId(d)
}
