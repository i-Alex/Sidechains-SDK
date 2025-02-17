package com.horizen.proof

import java.io.{BufferedReader, File, FileReader}
import java.util
import java.util.Optional
import com.google.common.io.Files
import com.horizen.box.WithdrawalRequestBox
import com.horizen.box.data.WithdrawalRequestBoxData
import com.horizen.cryptolibprovider.implementations.SchnorrFunctionsImplZendoo
import com.horizen.cryptolibprovider.{CommonCircuit, CryptoLibProvider}
import com.horizen.fixtures.FieldElementFixture
import com.horizen.mainchain.api.{CertificateRequestCreator, SendCertificateRequest}
import com.horizen.params.{NetworkParams, RegTestParams}
import com.horizen.proposition.MCPublicKeyHashProposition
import com.horizen.schnorrnative.SchnorrSecretKey
import com.horizen.utils.BytesUtils
import org.junit.Assert.{assertEquals, fail}
import org.junit.{Ignore, Test}

import scala.collection.JavaConverters._
import scala.util.Random

/**
 * This test was create for profiling of JVM memory use during proof generation.
 */

class ProofThreadTest {
  private val classLoader: ClassLoader = getClass.getClassLoader
  private val schnorrFunctions: SchnorrFunctionsImplZendoo = new SchnorrFunctionsImplZendoo()
  var proofWithQuality:com.horizen.utils.Pair[Array[Byte], java.lang.Long] = null

  private val tmpDir: File = Files.createTempDir
  private val provingKeyPath: String = tmpDir.getAbsolutePath + "/snark_pk"
  private val verificationKeyPath: String = tmpDir.getAbsolutePath + "/snark_vk"
  val keyPairsLen = 7

  private def buildSchnorrPrivateKey(index: Int): SchnorrSecretKey = {
    var bytes: Array[Byte] = null
    try {
      val resourceName = "schnorr_sk0"+ index + "_hex"
      val file = new FileReader(classLoader.getResource(resourceName).getFile)
      bytes = BytesUtils.fromHexString(new BufferedReader(file).readLine())
    }
    catch {
      case e: Exception =>
        assertEquals(e.toString(), true, false)
    }

    SchnorrSecretKey.deserialize(bytes)
  }

  case class DataForProofGeneration(sidechainId: Array[Byte],
                                    processedEpochNumber: Int,
                                    threshold: Int,
                                    withdrawalRequests: Seq[WithdrawalRequestBox],
                                    endCumulativeEpochBlockHash: Array[Byte],
                                    publicKeysBytes: util.List[Array[Byte]],
                                    signatures:util.List[Optional[Array[Byte]]],
                                    merkelTreeRoot: Array[Byte]
                                   )

  class MyThread(val dataForProofGeneration:DataForProofGeneration) extends Thread
  {
    override def run()
    {
      proofWithQuality = generateProof(dataForProofGeneration)
    }
  }

  def tryGenerateProof = {
    val params = new RegTestParams
    val dataForProofGeneration:DataForProofGeneration = buildDataForProofGeneration(params)

    var th = new MyThread(dataForProofGeneration)
    th.start()
    th.join()


    val certificateRequest: SendCertificateRequest = CertificateRequestCreator.create(
      params.sidechainId,
      dataForProofGeneration.processedEpochNumber,
      dataForProofGeneration.endCumulativeEpochBlockHash,
      proofWithQuality.getKey,
      proofWithQuality.getValue,
      dataForProofGeneration.withdrawalRequests,
      0,
      0,
      Seq(Array(0)),
      None,
      params)
    true
  }

  private def buildDataForProofGeneration(params: NetworkParams): DataForProofGeneration = {
    val keyPairsLen = 7
    val threshold = 5 //hardcoded value

    val sidechainId = params.sidechainId
    val epochNumber = 1
    val keyPairs = (0 until keyPairsLen).view.map(buildSchnorrPrivateKey).map(secret => (secret, secret.getPublicKey))
    val publicKeysBytes: util.List[Array[Byte]] = keyPairs.map(_._2.serializePublicKey()).toList.asJava
    val endCummulativeTransactionCommTreeHash = FieldElementFixture.generateFieldElement()
    val merkelTreeRoot = FieldElementFixture.generateFieldElement()

    val wb: Seq[WithdrawalRequestBox] = Seq(new WithdrawalRequestBox(new WithdrawalRequestBoxData(new MCPublicKeyHashProposition(Array.fill(20)(Random.nextInt().toByte)), 2345), 42))

    val message = CryptoLibProvider.sigProofThresholdCircuitFunctions.generateMessageToBeSigned(wb.asJava, sidechainId, epochNumber, endCummulativeTransactionCommTreeHash, 0, 0, Optional.of(merkelTreeRoot))
    val emptySigs = List.fill[Optional[Array[Byte]]](keyPairsLen - threshold)(Optional.empty[Array[Byte]]())

    val signatures: util.List[Optional[Array[Byte]]] = (keyPairs
      .map{case (secret, public) => schnorrFunctions.sign(secret.serializeSecretKey(), public.serializePublicKey(), message)}
      .map(b => Optional.of(b))
      .take(threshold)
      .toList ++ emptySigs)
      .asJava

    DataForProofGeneration(sidechainId, epochNumber, threshold, wb, endCummulativeTransactionCommTreeHash, publicKeysBytes, signatures, merkelTreeRoot)
  }

  private def generateProof(dataForProofGeneration: DataForProofGeneration): com.horizen.utils.Pair[Array[Byte], java.lang.Long] = {
    CryptoLibProvider.sigProofThresholdCircuitFunctions.createProof(dataForProofGeneration.withdrawalRequests.asJava, dataForProofGeneration.sidechainId, dataForProofGeneration.processedEpochNumber, dataForProofGeneration.endCumulativeEpochBlockHash, 0, 0, Optional.of(dataForProofGeneration.merkelTreeRoot), dataForProofGeneration.signatures, dataForProofGeneration.publicKeysBytes, dataForProofGeneration.threshold, provingKeyPath, true, true)
  }

  @Ignore
  @Test
  def simpleCheck(): Unit = {
    // Setup proving system keys
    println(s"Generating Marlin dlog key.")
    if (!CryptoLibProvider.commonCircuitFunctions.generateCoboundaryMarlinDLogKeys()) {
      fail("Error occurred during dlog key generation.")
    }

    println(s"Generating Marlin snark keys. Path: pk=$provingKeyPath, vk=$verificationKeyPath")
    if (!CryptoLibProvider.sigProofThresholdCircuitFunctions.generateCoboundaryMarlinSnarkKeys(keyPairsLen, provingKeyPath, verificationKeyPath, CommonCircuit.CUSTOM_FIELDS_NUMBER_WITH_ENABLED_CSW)) {
      fail("Error occurred during snark keys generation.")
    }

    for (i <- 1 to 15) {
      println("Thread proof generation # " + i)
      tryGenerateProof
    }
  }
}