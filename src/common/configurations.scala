package Common
{
   
case class SodorConfiguration()
{
   val xprlen = 32
   // Use async behavioral memory for RTL simulation when requested.
   // Keep SRAM22-backed memories for synthesis/physical flows.
   val useAsyncSim: Boolean = sys.env.get("SODOR_ASYNC_SIM").contains("1") || sys.props.get("SODOR_ASYNC_SIM").contains("1")
}


}
