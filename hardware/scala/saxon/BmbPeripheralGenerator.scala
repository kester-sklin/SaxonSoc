package saxon

import spinal.core._
import spinal.lib.bus.bmb.{Bmb, BmbAccessCapabilities, BmbAccessParameter, BmbImplicitPeripheralDecoder, BmbParameter, BmbSlaveFactory, BmbInterconnectGenerator}
import spinal.lib.bus.misc.{BusSlaveFactoryConfig, SizeMapping}
import spinal.lib.com.eth._
import spinal.lib.com.spi.ddr.SpiXdrMasterCtrl.XipBusParameters
import spinal.lib.com.spi.ddr.{BmbSpiXdrMasterCtrl, SpiXdrMasterCtrl}
import spinal.lib.com.uart.{BmbUartCtrl, UartCtrlMemoryMappedConfig}
import spinal.lib.generator.{Dependable, Export, Generator, Handle, InterruptCtrlGeneratorI, Unset}
import spinal.lib.io.{BmbGpio2, Gpio}
import spinal.lib.master
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.xdr.phy.XilinxS7Phy
import spinal.lib.memory.sdram.xdr.{BmbPortParameter, CoreParameter, CtrlParameter, CtrlWithPhy, CtrlWithoutPhy, CtrlWithoutPhyBmb, PhyLayout}
import spinal.lib.misc.{BmbClint, Clint}
import spinal.lib.misc.plic.{PlicGateway, PlicGatewayActiveHigh, PlicMapper, PlicMapping, PlicTarget}

import scala.collection.mutable.ArrayBuffer

case class BmbUartGenerator(apbOffset : Handle[BigInt] = Unset)
                            (implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator {
  val parameter = createDependency[UartCtrlMemoryMappedConfig]
  val interrupt = produce(logic.io.interrupt)
  val uart = produceIo(logic.io.uart)
  val bus = produce(logic.io.bus)

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]
  val logic = add task BmbUartCtrl(parameter, accessRequirements.toBmbParameter())

  val txd = uart.produce(uart.txd)
  val rxd = uart.produce(uart.rxd)

  def connectInterrupt(ctrl : InterruptCtrlGeneratorI, id : Int): Unit = {
    ctrl.addInterrupt(interrupt, id)
  }

  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(BmbUartCtrl.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = bus,
    mapping = apbOffset.derivate(SizeMapping(_, 1 << BmbUartCtrl.addressWidth))
  )
  export(parameter)
  if(decoder != null) interconnect.addConnection(decoder.bus, bus)
}


case class SdramXdrBmb2SmpGenerator(memoryAddress: BigInt)
                                  (implicit interconnect: BmbInterconnectGenerator) extends Generator {

  val phyParameter = createDependency[PhyLayout]
  val coreParameter = createDependency[CoreParameter]
  val portsParameter = ArrayBuffer[Handle[BmbPortParameter]]()
  val phyPort = produce(logic.io.phy)
  val ctrlBus = produce(logic.io.ctrl)


  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]
  def mapCtrlAt(address : BigInt)(implicit interconnect: BmbInterconnectGenerator) : this.type = {
    interconnect.addSlave(
      accessSource = accessSource,
      accessCapabilities = accessSource.derivate(CtrlWithoutPhyBmb.getBmbCapabilities),
      accessRequirements = accessRequirements,
      bus = ctrlBus,
      mapping = SizeMapping(address, 1 << CtrlWithoutPhyBmb.addressWidth)
    )
    this
  }

  def addPort() = new Generator {
    val requirements = createDependency[BmbAccessParameter]
    val portId = portsParameter.length
    val bmb = SdramXdrBmb2SmpGenerator.this.produce(logic.io.bmb(portId))

    portsParameter += SdramXdrBmb2SmpGenerator.this.createDependency[BmbPortParameter]

    interconnect.addSlave(
      accessCapabilities = phyParameter.produce(CtrlWithPhy.bmbCapabilities(phyParameter)),
      accessRequirements = requirements,
      bus = bmb,
      mapping = phyParameter.produce(SizeMapping(memoryAddress, phyParameter.sdram.capacity))
    )

    add task {
      portsParameter(portId).load(
        BmbPortParameter(
          bmb = requirements.toBmbParameter(),
          clockDomain = ClockDomain.current,
          cmdBufferSize = 16,
          dataBufferSize = 32,
          rspBufferSize = 32
        )
      )
    }
  }

  val logic = add task new CtrlWithoutPhyBmb(
    p =  CtrlParameter(
      core = coreParameter,
      ports = portsParameter.map(_.get)
    ),
    pl = phyParameter,
    ctrlParameter = accessRequirements.toBmbParameter()
  )
//  if(decoder != null) interconnect.addConnection(decoder.bus, ctrlBus)
}

case class XilinxS7PhyBmbGenerator(configAddress : BigInt)(implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator{
  val sdramLayout = createDependency[SdramLayout]
  val ctrl = produce(logic.ctrl)
  val sdram = produceIo(logic.phy.io.sdram)
  val clk90 = createDependency[ClockDomain]
  val serdesClk0 = createDependency[ClockDomain]
  val serdesClk90 = createDependency[ClockDomain]

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]

  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(CtrlWithoutPhyBmb.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = ctrl,
    mapping = SizeMapping(configAddress, 1 << CtrlWithoutPhyBmb.addressWidth)
  )

  val logic = add task new Area{
    val phy = XilinxS7Phy(
      sl = sdramLayout,
      clkRatio = 2,
      clk90 = clk90,
      serdesClk0 = serdesClk0,
      serdesClk90 = serdesClk90
    )
    val ctrl = Bmb(accessRequirements)
    phy.driveFrom(BmbSlaveFactory(ctrl))
  }


  def connect(ctrl : SdramXdrBmb2SmpGenerator): Unit = {
    ctrl.phyParameter.derivatedFrom(sdramLayout)(XilinxS7Phy.phyLayout(_, 2))
//    this.produce{
//      ctrl.phyParameter.load(logic.phy.pl)
//    }
    List(ctrl.logic, logic).produce{
      ctrl.logic.io.phy <> logic.phy.io.ctrl
    }
  }
  if(decoder != null) interconnect.addConnection(decoder.bus, ctrl)
}



case class  BmbGpioGenerator(apbOffset : Handle[BigInt] = Unset)
                             (implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator{
  val parameter = createDependency[spinal.lib.io.Gpio.Parameter]
  val gpio = produceIo(logic.io.gpio)
  val bus = produce(logic.io.bus)

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]
  //TODO not having to setCompositeName
  val interrupts : Handle[List[Handle[Bool]]] = parameter.produce(List.tabulate(parameter.width)(i => this.produce(logic.io.interrupt(i)).setCompositeName(interrupts, i.toString)))
  val logic = add task BmbGpio2(parameter, accessRequirements.toBmbParameter())

  @dontName var interruptCtrl : InterruptCtrlGeneratorI = null
  var interruptOffsetId = 0
  def connectInterrupts(ctrl : InterruptCtrlGeneratorI, offsetId : Int): Unit = interrupts.produce{
    for(pinId <- parameter.interrupt) ctrl.addInterrupt(interrupts.get(pinId), offsetId + pinId)
    interruptCtrl = ctrl
    interruptOffsetId = offsetId
  }
  def connectInterrupt(ctrl : InterruptCtrlGeneratorI, pinId : Int, interruptId : Int): Unit = interrupts.produce{
    ctrl.addInterrupt(interrupts.get(pinId), interruptId)
  }
  def pin(id : Int) = gpio.produce(gpio.get.setAsDirectionLess.apply(id))

  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(BmbGpio2.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = bus,
    mapping = apbOffset.derivate(SizeMapping(_, 1 << Gpio.addressWidth))
  )
  if(decoder != null) interconnect.addConnection(decoder.bus, bus)
}





object BmbSpiGenerator{
  def apply(apbOffset : BigInt, xipOffset : BigInt = 0)
           (implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null): BmbSpiGenerator ={
    new BmbSpiGenerator(apbOffset,xipOffset)
  }
}
class BmbSpiGenerator(apbOffset : Handle[BigInt] = Unset, xipOffset : Handle[BigInt] = 0)
                      (implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator {
  val parameter = createDependency[SpiXdrMasterCtrl.MemoryMappingParameters]
  val withXip = Handle(false)
  val interrupt = produce(logic.io.interrupt)
  val phy = produce(logic.io.spi)
  val spi = Handle[Nameable]
  val ctrl : Handle[Bmb] = produce(logic.io.ctrl)

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]

  val logic = add task BmbSpiXdrMasterCtrl(parameter, accessRequirements.toBmbParameter())
//  val logic = add task BmbSpiXdrMasterCtrl(parameter.copy(xip = if(!withXip) null else XipBusParameters(24, bmbRequirements.lengthWidth)), accessRequirements.toBmbParameter())

  val bmbRequirements = Handle[BmbParameter]
  val bmb = product[Bmb]

  dependencies += withXip.produce{
    if(withXip) {
      ???
//      dependencies += bmbRequirements
//      interconnect.addSlaveAt(
//        capabilities = Handle(SpiXdrMasterCtrl.getXipBmbCapabilities()),
//        requirements = bmbRequirements,
//        bus = bmb,
//        address = xipOffset
//      )
//      Dependable(BmbSpiGenerator.this, bmbRequirements){
//        bmb.load(logic.io.xip.fromBmb(bmbRequirements))
//      }
    }
  }


  dependencies += withXip

  @dontName var interruptCtrl : InterruptCtrlGeneratorI = null
  var interruptId = 0
  def connectInterrupt(ctrl : InterruptCtrlGeneratorI, id : Int): Unit = {
    ctrl.addInterrupt(interrupt, id)
    interruptCtrl = ctrl
    interruptId = id
  }

  def inferSpiSdrIo() = this(Dependable(phy)(spi.load(master(phy.toSpi().setPartialName(spi, ""))))) //TODO automated naming
  def inferSpiIce40() = this(Dependable(phy)(spi.load{
    phy.toSpiIce40().asInOut().setPartialName(spi, "")
  }))
  def phyAsIo() = produceIo(phy.get)

  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(BmbSpiXdrMasterCtrl.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = ctrl,
    mapping = apbOffset.derivate(SizeMapping(_, 1 << BmbSpiXdrMasterCtrl.addressWidth))
  )
  if(decoder != null) interconnect.addConnection(decoder.bus, ctrl)
}

case class BmbMacEthGenerator(address : Handle[BigInt] = Unset)
                             (implicit interconnect: BmbInterconnectGenerator, decoder : BmbImplicitPeripheralDecoder = null) extends Generator {
  val parameter = createDependency[MacEthParameter]
  val rxCd, txCd = createDependency[ClockDomain]
  val interrupt = produce(logic.io.interrupt)
  val phy = produce(logic.io.phy)
  val bus = produce(logic.io.bus)

  val accessSource = Handle[BmbAccessCapabilities]
  val accessRequirements = createDependency[BmbAccessParameter]
  val logic = add task BmbMacEth(
      p            = parameter,
      bmbParameter = accessRequirements.toBmbParameter(),
      txCd         = txCd,
      rxCd         = rxCd
  )

  def connectInterrupt(ctrl : InterruptCtrlGeneratorI, id : Int): Unit = {
    ctrl.addInterrupt(interrupt, id)
  }

  interconnect.addSlave(
    accessSource = accessSource,
    accessCapabilities = accessSource.derivate(BmbMacEth.getBmbCapabilities),
    accessRequirements = accessRequirements,
    bus = bus,
    mapping = address.derivate(SizeMapping(_, 1 << BmbMacEth.addressWidth))
  )
  export(parameter)
  if(decoder != null) interconnect.addConnection(decoder.bus, bus)


  def withPhyMii() = new Generator {
    val mii = add task master(Mii(
      MiiParameter(
        MiiTxParameter(
          dataWidth = 4,
          withEr    = false
        ),
        MiiRxParameter(
          dataWidth = 4
        )
      )
    ))

    txCd.derivatedFrom(mii)(_ => ClockDomain(mii.TX.CLK))
    rxCd.derivatedFrom(mii)(_ => ClockDomain(mii.RX.CLK))

    List(mii, phy).produce{
      txCd.copy(reset = logic.mac.txReset) on {
        val tailer = MacTxInterFrame(dataWidth = 4)
        tailer.io.input << phy.tx

        mii.TX.EN := RegNext(tailer.io.output.valid)
        mii.TX.D := RegNext(tailer.io.output.data)
      }
      rxCd on {
        phy.rx << mii.RX.toRxFlow().toStream
      }
    }
  }

  def withPhyRmii() = new Generator {
    val mii = add task master(Rmii(
      RmiiParameter(
        RmiiTxParameter(
          dataWidth = 2
        ),
        RmiiRxParameter(
          dataWidth = 2,
          withEr    = true
        )
      )
    ))

    List(mii, phy).produce{
      txCd.copy(reset = logic.mac.txReset) on {
        mii.TX.fromTxStream() << phy.tx
      }
      rxCd on {
        phy.rx << mii.RX.toRxFlow().toStream
      }
    }
  }
}
