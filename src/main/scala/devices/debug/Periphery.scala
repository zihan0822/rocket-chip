// See LICENSE.SiFive for license details.

package freechips.rocketchip.devices.debug

import chisel3._
import chisel3.experimental.{noPrefix, IntParam}
import chisel3.util._

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._

import freechips.rocketchip.amba.apb.{APBBundle, APBBundleParameters, APBMasterNode, APBMasterParameters, APBMasterPortParameters}
import freechips.rocketchip.interrupts.{IntSyncXbar, NullIntSyncSource}
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.prci.{ClockSinkNode, ClockSinkParameters}
import freechips.rocketchip.subsystem.{BaseSubsystem, CBUS, FBUS, ResetSynchronous, SubsystemResetSchemeKey, TLBusWrapperLocation}
import freechips.rocketchip.tilelink.{TLFragmenter, TLWidthWidget}
import freechips.rocketchip.util.{AsyncResetSynchronizerShiftReg, CanHavePSDTestModeIO, ClockGate, PSDTestMode, PlusArg, ResetSynchronizerShiftReg}

import freechips.rocketchip.util.BooleanToAugmentedBoolean

/** Protocols used for communicating with external debugging tools */
sealed trait DebugExportProtocol
case object DMI extends DebugExportProtocol
case object JTAG extends DebugExportProtocol
case object CJTAG extends DebugExportProtocol
case object APB extends DebugExportProtocol

/** Options for possible debug interfaces */
case class DebugAttachParams(
  protocols: Set[DebugExportProtocol] = Set(DMI),
  externalDisable: Boolean = false,
  masterWhere: TLBusWrapperLocation = FBUS,
  slaveWhere: TLBusWrapperLocation = CBUS
) {
  def dmi   = protocols.contains(DMI)
  def jtag  = protocols.contains(JTAG)
  def cjtag = protocols.contains(CJTAG)
  def apb   = protocols.contains(APB)
}

case object ExportDebug extends Field(DebugAttachParams())

class ClockedAPBBundle(params: APBBundleParameters) extends APBBundle(params) {
  val clock = Clock()
  val reset = Reset()
}


class DebugIO(implicit val p: Parameters) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val clockeddmi = p(ExportDebug).dmi.option(Flipped(new ClockedDMIIO()))
  val apb = p(ExportDebug).apb.option(Flipped(new ClockedAPBBundle(APBBundleParameters(addrBits=12, dataBits=32))))
  //------------------------------
  val ndreset    = Output(Bool())
  val dmactive   = Output(Bool())
  val dmactiveAck = Input(Bool())
  val extTrigger = (p(DebugModuleKey).get.nExtTriggers > 0).option(new DebugExtTriggerIO())
  val disableDebug = p(ExportDebug).externalDisable.option(Input(Bool()))
}

class PSDIO(implicit val p: Parameters) extends Bundle with CanHavePSDTestModeIO {
}

class ResetCtrlIO(val nComponents: Int)(implicit val p: Parameters) extends Bundle {
  val hartResetReq = (p(DebugModuleKey).exists(x=>x.hasHartResets)).option(Output(Vec(nComponents, Bool())))
  val hartIsInReset = Input(Vec(nComponents, Bool()))
}

/** Either adds a JTAG DTM to system, and exports a JTAG interface,
  * or exports the Debug Module Interface (DMI), or exports and hooks up APB,
  * based on a global parameter.
  */

trait HasPeripheryDebug { this: BaseSubsystem =>
  private lazy val tlbus = locateTLBusWrapper(p(ExportDebug).slaveWhere)

  lazy val debugCustomXbarOpt = p(DebugModuleKey).map(params => LazyModule( new DebugCustomXbar(outputRequiresInput = false)))
  lazy val apbDebugNodeOpt = p(ExportDebug).apb.option(APBMasterNode(Seq(APBMasterPortParameters(Seq(APBMasterParameters("debugAPB"))))))
  val debugTLDomainOpt = p(DebugModuleKey).map { _ =>
    val domain = ClockSinkNode(Seq(ClockSinkParameters()))
    domain := tlbus.fixedClockNode
    domain
  }
  lazy val debugOpt = p(DebugModuleKey).map { params =>
    val tlDM = LazyModule(new TLDebugModule(tlbus.beatBytes))

    tlDM.node := tlbus.coupleTo("debug"){ TLFragmenter(tlbus.beatBytes, tlbus.blockBytes, nameSuffix = Some("Debug")) := _ }
    tlDM.dmInner.dmInner.customNode := debugCustomXbarOpt.get.node

    (apbDebugNodeOpt zip tlDM.apbNodeOpt) foreach { case (master, slave) =>
      slave := master
    }

    tlDM.dmInner.dmInner.sb2tlOpt.foreach { sb2tl  =>
      locateTLBusWrapper(p(ExportDebug).masterWhere).coupleFrom("debug_sb") {
        _ := TLWidthWidget(1) := sb2tl.node
      }
    }
    tlDM
  }

  val debugNode = debugOpt.map(_.intnode)

  val psd = InModuleBody {
    val psd = IO(new PSDIO)
    psd
  }

  val resetctrl = InModuleBody {
    debugOpt.map { debug =>
      debug.module.io.tl_reset := debugTLDomainOpt.get.in.head._1.reset
      debug.module.io.tl_clock := debugTLDomainOpt.get.in.head._1.clock
      val resetctrl = IO(new ResetCtrlIO(debug.dmOuter.dmOuter.intnode.edges.out.size))
      debug.module.io.hartIsInReset := resetctrl.hartIsInReset
      resetctrl.hartResetReq.foreach { rcio => debug.module.io.hartResetReq.foreach { rcdm => rcio := rcdm }}
      resetctrl
    }
  }

  // noPrefix is workaround https://github.com/freechipsproject/chisel3/issues/1603
  val debug = InModuleBody { noPrefix(debugOpt.map { debugmod =>
    val debug = IO(new DebugIO)

    require(!(debug.clockeddmi.isDefined && debug.apb.isDefined),
      "You cannot have both DMI and APB interface in HasPeripheryDebug")

    debug.clockeddmi.foreach { dbg => debugmod.module.io.dmi.get <> dbg }

    (debug.apb
      zip apbDebugNodeOpt
      zip debugmod.module.io.apb_clock
      zip debugmod.module.io.apb_reset).foreach {
      case (((io, apb), c ), r) =>
        apb.out(0)._1 <> io
        c:= io.clock
        r:= io.reset
    }

    debugmod.module.io.debug_reset := debug.reset
    debugmod.module.io.debug_clock := debug.clock

    debug.ndreset := debugmod.module.io.ctrl.ndreset
    debug.dmactive := debugmod.module.io.ctrl.dmactive
    debugmod.module.io.ctrl.dmactiveAck := debug.dmactiveAck
    debug.extTrigger.foreach { x => debugmod.module.io.extTrigger.foreach {y => x <> y}}

    // TODO in inheriting traits: Set this to something meaningful, e.g. "component is in reset or powered down"
    debugmod.module.io.ctrl.debugUnavail.foreach { _ := false.B }

    debug
  })}

}

object Debug {
  def connectDebug(
      debugOpt: Option[DebugIO],
      resetctrlOpt: Option[ResetCtrlIO],
      psdio: PSDIO,
      c: Clock,
      r: Bool,
      harnessProxy: DMIIO,
      tckHalfPeriod: Int = 2,
      cmdDelay: Int = 2,
      psd: PSDTestMode = 0.U.asTypeOf(new PSDTestMode()))
      (implicit p: Parameters): Unit =  {
    connectDebugClockAndReset(debugOpt, c)
    resetctrlOpt.map { rcio => rcio.hartIsInReset.map { _ := r }}
    debugOpt.map { debug =>
      debug.clockeddmi.foreach { d =>
        d.dmi <> harnessProxy
        d.dmiClock := c 
        d.dmiReset := r 
      }
      debug.apb.foreach { apb =>
        require(false, "No support for connectDebug for an APB debug connection.")
      }
      psdio.psd.foreach { _ <> psd }
      debug.disableDebug.foreach { x => x := false.B }
    }
  }

  def connectDebugClockAndReset(debugOpt: Option[DebugIO], c: Clock, sync: Boolean = true)(implicit p: Parameters): Unit = {
    debugOpt.foreach { debug =>
      val dmi_reset = debug.clockeddmi.map(_.dmiReset.asBool).getOrElse(false.B) |
        debug.apb.map(_.reset.asBool).getOrElse(false.B)
      connectDebugClockHelper(debug, dmi_reset, c, sync)
    }
  }

  def connectDebugClockHelper(debug: DebugIO, dmi_reset: Reset, c: Clock, sync: Boolean = true)(implicit p: Parameters): Unit = {
    val debug_reset = Wire(Bool())
    withClockAndReset(c, dmi_reset) {
      val debug_reset_syncd = if(sync) ~AsyncResetSynchronizerShiftReg(in=true.B, sync=3, name=Some("debug_reset_sync")) else dmi_reset
      debug_reset := debug_reset_syncd
    }
    // Need to clock DM during debug_reset because of synchronous reset, so keep
    // the clock alive for one cycle after debug_reset asserts to action this behavior.
    // The unit should also be clocked when dmactive is high.
    withClockAndReset(c, debug_reset.asAsyncReset) {
      val dmactiveAck = if (sync) ResetSynchronizerShiftReg(in=debug.dmactive, sync=3, name=Some("dmactiveAck")) else debug.dmactive
      val clock_en = RegNext(next=dmactiveAck, init=true.B)
      val gated_clock =
        if (!p(DebugModuleKey).get.clockGate) c
        else ClockGate(c, clock_en, "debug_clock_gate")
      debug.clock := gated_clock
      debug.reset := (if (p(SubsystemResetSchemeKey)==ResetSynchronous) debug_reset else debug_reset.asAsyncReset)
      debug.dmactiveAck := dmactiveAck
    }
  }

  def tieoffDebug(debugOpt: Option[DebugIO], resetctrlOpt: Option[ResetCtrlIO] = None, psdio: Option[PSDIO] = None)(implicit p: Parameters): Bool = {

    psdio.foreach(_.psd.foreach { _ <> 0.U.asTypeOf(new PSDTestMode()) } )
    resetctrlOpt.map { rcio => rcio.hartIsInReset.map { _ := false.B }}
    debugOpt.map { debug =>
      debug.clock := true.B.asClock
      debug.reset := (if (p(SubsystemResetSchemeKey)==ResetSynchronous) true.B else true.B.asAsyncReset)

      debug.clockeddmi.foreach { d =>
        d.dmi.req.valid := false.B
        d.dmi.req.bits.addr := 0.U
        d.dmi.req.bits.data := 0.U
        d.dmi.req.bits.op := 0.U
        d.dmi.resp.ready := true.B
        d.dmiClock := false.B.asClock
        d.dmiReset := true.B.asAsyncReset
      }

      debug.apb.foreach { apb =>
        apb.clock := false.B.asClock
        apb.reset := true.B.asAsyncReset
        apb.pready := false.B
        apb.pslverr := false.B
        apb.prdata := 0.U
        apb.pduser := 0.U.asTypeOf(chiselTypeOf(apb.pduser))
        apb.psel := false.B
        apb.penable := false.B
      }

      debug.extTrigger.foreach { t =>
        t.in.req := false.B
        t.out.ack := t.out.req
      }
      debug.disableDebug.foreach { x => x := false.B }
      debug.dmactiveAck := false.B
      debug.ndreset
    }.getOrElse(false.B)
  }
}
