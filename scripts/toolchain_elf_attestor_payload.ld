ENTRY(AgentCodiElfAttestorEntry)

SECTIONS
{
  . = 0;
  .text : ALIGN(16)
  {
    KEEP(*(.text.agentcodi_entry))
    *(.text .text.*)
  }
  .rodata : ALIGN(16)
  {
    *(.rodata .rodata.*)
  }
  .agentcodi_config : ALIGN(8)
  {
    KEEP(*(.agentcodi_config))
  }
  /DISCARD/ :
  {
    *(.comment)
    *(.note*)
    *(.eh_frame*)
    *(.ARM.exidx*)
    *(.ARM.extab*)
  }
}

ASSERT(AgentCodiElfAttestorEntry == 0,
       "AGENTCODI ELF attestor entry must be the first payload byte")
