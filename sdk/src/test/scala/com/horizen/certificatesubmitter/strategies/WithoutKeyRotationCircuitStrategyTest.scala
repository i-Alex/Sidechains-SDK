package com.horizen.certificatesubmitter.strategies

import akka.util.Timeout
import com.horizen._
import com.horizen.block.SidechainCreationVersions
import com.horizen.box.WithdrawalRequestBox
import com.horizen.certificatesubmitter.CertificateSubmitter.{CertificateSignatureInfo, SignaturesStatus}
import com.horizen.certificatesubmitter.dataproof.CertificateDataWithoutKeyRotation
import com.horizen.certificatesubmitter.keys.SchnorrKeysSignatures
import com.horizen.chain.{MainchainHeaderInfo, SidechainBlockInfo}
import com.horizen.cryptolibprovider.ThresholdSignatureCircuit
import com.horizen.cryptolibprovider.implementations.ThresholdSignatureCircuitImplZendoo
import com.horizen.fork.{ForkManagerUtil, SimpleForkConfigurator}
import com.horizen.librustsidechains.FieldElement
import com.horizen.node.util.MainchainBlockReferenceInfo
import com.horizen.params.RegTestParams
import com.horizen.proof.SchnorrProof
import com.horizen.proposition.SchnorrProposition
import com.horizen.schnorrnative.SchnorrKeyPair
import com.horizen.secret.{SchnorrKeyGenerator, SchnorrSecret}
import com.horizen.storage.SidechainHistoryStorage
import org.junit.{Before, Test}
import org.mockito.Mockito.when
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatestplus.junit.JUnitSuite
import org.scalatestplus.mockito.MockitoSugar
import scorex.util.ModifierId
import sparkz.core.NodeViewHolder.CurrentView
import sparkz.core.settings.{RESTApiSettings, SparkzSettings}

import java.lang
import java.util.{Optional => JOptional}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

class WithoutKeyRotationCircuitStrategyTest extends JUnitSuite with MockitoSugar {
  implicit val timeout: Timeout = 100 milliseconds
  var params: RegTestParams = RegTestParams()

  @Before
  def init(): Unit = {
    val keyGenerator = SchnorrKeyGenerator.getInstance()
    val schnorrSecrets: Seq[SchnorrSecret] = Seq(
      keyGenerator.generateSecret("seed1".getBytes()),
      keyGenerator.generateSecret("seed2".getBytes()),
      keyGenerator.generateSecret("seed3".getBytes())
    )
    val signersThreshold = 2
    params = RegTestParams(
      sidechainCreationVersion = SidechainCreationVersions.SidechainCreationVersion2,
      signersPublicKeys = schnorrSecrets.map(_.publicImage()),
      mastersPublicKeys = Seq(),
      signersThreshold = signersThreshold
    )

    val forkManagerUtil = new ForkManagerUtil()
    forkManagerUtil.initializeForkManager(new SimpleForkConfigurator(), "regtest")
  }

  @Test
  def buildCertificateDataTest(): Unit = {
    val certificateSignatureInfo = CertificateSignatureInfo(pubKeyIndex = 3,
      signature = new SchnorrProof(WithoutKeyRotationCircuitStrategyTest.signing.signMessage(WithoutKeyRotationCircuitStrategyTest.signing.getPublicKey.getHash).serializeSignature()))
    val signaturesStatus = SignaturesStatus(
      referencedEpoch = WithoutKeyRotationCircuitStrategyTest.epochNumber,
      messageToSign = Array(135.toByte),
      knownSigs = ArrayBuffer(certificateSignatureInfo)
    )
    val keyRotationStrategy: CircuitStrategy[CertificateDataWithoutKeyRotation] = new WithoutKeyRotationCircuitStrategy(settings(), params, mock[ThresholdSignatureCircuit])
    val certificateDataWithoutKeyRotation: CertificateDataWithoutKeyRotation = keyRotationStrategy.buildCertificateData(sidechainNodeView(), signaturesStatus)
    assert(certificateDataWithoutKeyRotation.utxoMerkleTreeRoot.isDefined)
    assertResult(10)(certificateDataWithoutKeyRotation.referencedEpochNumber)
    assertResult(32)(certificateDataWithoutKeyRotation.sidechainId.length)
    assertResult(0)(certificateDataWithoutKeyRotation.withdrawalRequests.size)
    assertResult(32)(certificateDataWithoutKeyRotation.endEpochCumCommTreeHash.length)
    assertResult(0)(certificateDataWithoutKeyRotation.btrFee)
    assertResult(0)(certificateDataWithoutKeyRotation.ftMinAmount)
    assertResult(3)(certificateDataWithoutKeyRotation.schnorrKeyPairs.length)
  }

  @Test
  def generateProofTest(): Unit = {
    val info = for (_ <- 0 until WithoutKeyRotationCircuitStrategyTest.keyCount) yield {
      val signerKeyPair: SchnorrKeyPair = SchnorrKeyPair.generate
      val masterKeyPair: SchnorrKeyPair = SchnorrKeyPair.generate

      val updatedSigningKeysSkSignature = signerKeyPair.signMessage(signerKeyPair.getPublicKey.getHash)
      val updatedSigningKeysMkSignature = masterKeyPair.signMessage(signerKeyPair.getPublicKey.getHash)
      val updatedMasterKeysSkSignature = signerKeyPair.signMessage(masterKeyPair.getPublicKey.getHash)
      val updatedMasterKeysMkSignature = masterKeyPair.signMessage(masterKeyPair.getPublicKey.getHash)
      (signerKeyPair.getPublicKey, masterKeyPair.getPublicKey, updatedSigningKeysSkSignature,
        updatedSigningKeysMkSignature, updatedMasterKeysSkSignature, updatedMasterKeysMkSignature)
    }

    val schnorrKeysSignatures = SchnorrKeysSignatures(
      info.map(_._1.serializePublicKey()).map(b => new SchnorrProposition(b)),
      info.map(_._2.serializePublicKey()).map(b => new SchnorrProposition(b)),
      info.map(_._1.serializePublicKey()).map(b => new SchnorrProposition(b)),
      info.map(_._2.serializePublicKey()).map(b => new SchnorrProposition(b)),
      info.map(x => Option.apply(new SchnorrProof(x._3.serializeSignature()))),
      info.map(x => Option.apply(new SchnorrProof(x._4.serializeSignature()))),
      info.map(x => Option.apply(new SchnorrProof(x._5.serializeSignature()))),
      info.map(x => Option.apply(new SchnorrProof(x._6.serializeSignature())))
    )

    val schnorrPropositionsAndSchnorrProofs: Seq[(SchnorrProposition, Option[SchnorrProof])] =
      for (i <- 0 until WithoutKeyRotationCircuitStrategyTest.keyCount) yield {
        (schnorrKeysSignatures.newSchnorrSigners(i),
          Some(schnorrKeysSignatures.updatedSigningKeysSkSignatures(i).get))
      }


    val certificateData = CertificateDataWithoutKeyRotation(
      referencedEpochNumber = WithoutKeyRotationCircuitStrategyTest.epochNumber,
      sidechainId = FieldElement.createRandom.serializeFieldElement(),
      withdrawalRequests = Seq[WithdrawalRequestBox](),
      endEpochCumCommTreeHash = FieldElement.createRandom.serializeFieldElement(),
      btrFee = WithoutKeyRotationCircuitStrategyTest.btrFee,
      ftMinAmount = WithoutKeyRotationCircuitStrategyTest.ftMinAmount,
      schnorrKeyPairs = schnorrPropositionsAndSchnorrProofs,
      Some(Array())
    )

    val mockedCryptolibCircuit = mock[ThresholdSignatureCircuit]
    val key = new Array(4.toByte)
    Mockito.when(mockedCryptolibCircuit.createProof(ArgumentMatchers.anyList[WithdrawalRequestBox](), ArgumentMatchers.any(), ArgumentMatchers.any(),
      ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
      ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
      ArgumentMatchers.any())) thenAnswer (answer => {
      assertResult(32)(answer.getArgument(1).asInstanceOf[Array[Byte]].length)
      assertResult(10)(answer.getArgument(2).asInstanceOf[Integer])
      assertResult(32)(answer.getArgument(3).asInstanceOf[Array[Byte]].length)
      assertResult(100L)(answer.getArgument(4).asInstanceOf[Long])
      assertResult(200L)(answer.getArgument(5).asInstanceOf[Long])
      assert(answer.getArgument(6).asInstanceOf[java.util.Optional[Byte]].isPresent)
      assertResult(2L)(answer.getArgument(9).asInstanceOf[Long])
      assertResult("filePath")(answer.getArgument(10).asInstanceOf[String])
      assertResult(true)(answer.getArgument(11).asInstanceOf[Boolean]) // compressedPk
      assertResult(true)(answer.getArgument(12).asInstanceOf[Boolean]) // compressProof
      new com.horizen.utils.Pair(key, 425L)
    })
    val keyRotationStrategy: CircuitStrategy[CertificateDataWithoutKeyRotation] = new WithoutKeyRotationCircuitStrategy(settings(), params, mockedCryptolibCircuit)
    val pair: utils.Pair[Array[Byte], lang.Long] = keyRotationStrategy.generateProof(certificateData, provingFileAbsolutePath = "filePath")
    assert(pair.getValue == 425L)
    info.foreach(element => {
      element._3.freeSignature()
      element._4.freeSignature()
      element._5.freeSignature()
      element._6.freeSignature()
    })
  }

  @Test
  def getMessageToSignTest(): Unit = {
    val mockedCryptolibCircuit = mock[ThresholdSignatureCircuit]
    Mockito.when(mockedCryptolibCircuit.generateMessageToBeSigned(ArgumentMatchers.any(), ArgumentMatchers.any(),
      ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
      ArgumentMatchers.any())) thenAnswer (answer => {
      assertResult(32)(answer.getArgument(1).asInstanceOf[Array[Byte]].length)
      assertResult(10)(answer.getArgument(2).asInstanceOf[Integer])
      assertResult(32)(answer.getArgument(3).asInstanceOf[Array[Byte]].length)
      assertResult(2L)(answer.getArgument(4).asInstanceOf[Long])
      assertResult(2L)(answer.getArgument(5).asInstanceOf[Long])
      assert(answer.getArgument(6).asInstanceOf[java.util.Optional[Byte]].isPresent)
      new com.horizen.utils.Pair(Array(67.toByte), 425L)
    })
    val keyRotationStrategy: CircuitStrategy[CertificateDataWithoutKeyRotation] = new WithoutKeyRotationCircuitStrategy(settings(), params, mockedCryptolibCircuit)
    keyRotationStrategy.getMessageToSign(sidechainNodeView(), WithoutKeyRotationCircuitStrategyTest.epochNumber)
  }

  private def settings(): SidechainSettings = {
    val signerIsEnabled = true
    val submitterIsEnabled = true
    val mockedRESTSettings: RESTApiSettings = mock[RESTApiSettings]
    when(mockedRESTSettings.timeout).thenReturn(timeout.duration)

    val mockedSidechainSettings: SidechainSettings = mock[SidechainSettings]
    when(mockedSidechainSettings.sparkzSettings).thenAnswer(_ => {
      val mockedSparkzSettings: SparkzSettings = mock[SparkzSettings]
      when(mockedSparkzSettings.restApi).thenAnswer(_ => mockedRESTSettings)
      mockedSparkzSettings
    })
    when(mockedSidechainSettings.withdrawalEpochCertificateSettings).thenAnswer(_ => {
      val mockedWithdrawalEpochCertificateSettings: WithdrawalEpochCertificateSettings = mock[WithdrawalEpochCertificateSettings]
      when(mockedWithdrawalEpochCertificateSettings.submitterIsEnabled).thenReturn(submitterIsEnabled)
      when(mockedWithdrawalEpochCertificateSettings.certificateSigningIsEnabled).thenReturn(signerIsEnabled)
      mockedWithdrawalEpochCertificateSettings
    })
    mockedSidechainSettings
  }

  private def sidechainNodeView(): CurrentView[SidechainHistory, SidechainState, SidechainWallet, SidechainMemoryPool] = {
    val sidechainState = mock[SidechainState]
    when(sidechainState.utxoMerkleTreeRoot(ArgumentMatchers.anyInt())).thenAnswer(_ => Some(new Array[Byte](32)))
    when(sidechainState.withdrawalRequests(ArgumentMatchers.anyInt())).thenAnswer(_ => Seq())
    val history = mock[SidechainHistory]
    when(history.mainchainHeaderInfoByHash(ArgumentMatchers.any[Array[Byte]])).thenAnswer(_ => {
      val info: MainchainHeaderInfo = mock[MainchainHeaderInfo]
      when(info.cumulativeCommTreeHash).thenReturn(new Array[Byte](32))
      when(info.sidechainBlockId).thenReturn(ModifierId @@ "some_block_id")
      Some(info)
    })
    val mainchainBlockMock = mock[MainchainBlockReferenceInfo]
    when(history.getMainchainBlockReferenceInfoByMainchainBlockHeight(ArgumentMatchers.anyInt())).thenAnswer(_ => JOptional.of(mainchainBlockMock))
    when(mainchainBlockMock.getMainchainHeaderHash).thenAnswer(_ => Array(13.toByte))
    val sidechainBlockInfo = mock[SidechainBlockInfo]
    val historyStorageMock: SidechainHistoryStorage = mock[SidechainHistoryStorage]
    when(history.storage).thenAnswer(_ => historyStorageMock)
    when(historyStorageMock.blockInfoById(ArgumentMatchers.any[ModifierId])).thenAnswer(_ => sidechainBlockInfo)
    when(sidechainBlockInfo.timestamp).thenAnswer(_ => params.sidechainGenesisBlockTimestamp + 1)
    CurrentView(history, sidechainState, mock[SidechainWallet], mock[SidechainMemoryPool])
  }
}

object WithoutKeyRotationCircuitStrategyTest {
  private val epochNumber = 10
  private val btrFee = 100L
  private val ftMinAmount = 200L
  val DLOG_KEYS_SIZE: Int = 1 << 18
  val CERT_SEGMENT_SIZE: Int = 1 << 15
  val CSW_SEGMENT_SIZE: Int = 1 << 18
  private val keyCount = 4
  val tresholdCircuit = new ThresholdSignatureCircuitImplZendoo
  val signing: SchnorrKeyPair = SchnorrKeyPair.generate()
  val master: SchnorrKeyPair = SchnorrKeyPair.generate()
}
