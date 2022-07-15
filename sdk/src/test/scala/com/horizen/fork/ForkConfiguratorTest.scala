package com.horizen.fork

import org.junit.Assert.assertEquals
import org.junit.Test
import org.scalatestplus.junit.JUnitSuite

class BadForkConfigurator extends ForkConfigurator {
  override def getBaseSidechainConsensusEpochNumbers(): scConsensusEpochNumber = scConsensusEpochNumber(-5 ,0 ,0)
}

class ForkConfiguratorTest extends JUnitSuite {
  val badForkConfigurator = new BadForkConfigurator()
  val simpleForkConfigurator = new SimpleForkConfigurator()

  @Test
  def testConfiguration(): Unit = {
    assertEquals("Expected failed check", false, badForkConfigurator.check().isSuccess)
    assertEquals("Expected successful check", true, simpleForkConfigurator.check().isSuccess)
  }
}
