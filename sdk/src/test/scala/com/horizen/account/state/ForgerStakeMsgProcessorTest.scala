package com.horizen.account.state

import com.horizen.account.proof.SignatureSecp256k1
import com.horizen.account.proposition.AddressProposition
import com.horizen.account.secret.PrivateKeySecp256k1
import com.horizen.account.state.ForgerStakeMsgProcessor.{GetListOfForgersCmd, getMessageToSign, getStakeId}
import com.horizen.account.storage.AccountStateMetadataStorageView
import com.horizen.consensus
import com.horizen.evm.{LevelDBDatabase, StateDB}
import com.horizen.proposition.{PublicKey25519Proposition, VrfPublicKey}
import com.horizen.utils.{BytesUtils, ListSerializer}
import org.junit.Assert._
import org.junit._
import org.junit.rules.TemporaryFolder
import org.mockito._
import org.scalatestplus.junit.JUnitSuite
import org.scalatestplus.mockito._
import org.web3j.crypto.{Keys, Sign}

import java.math.BigInteger


@Ignore
class ForgerStakeMsgProcessorTest
  extends JUnitSuite
    with MockitoSugar
{
  var tempFolder = new TemporaryFolder

  @Before
  def setUp() : Unit = {
  }

  def getView() : AccountStateView = {
    tempFolder.create()
    val databaseFolder = tempFolder.newFolder("evm-db" + Math.random())
    val hashNull = BytesUtils.fromHexString("0000000000000000000000000000000000000000000000000000000000000000")
    val db = new LevelDBDatabase(databaseFolder.getAbsolutePath())
    val messageProcessors: Seq[MessageProcessor] = Seq()
    val metadataStorageView: AccountStateMetadataStorageView = mock[AccountStateMetadataStorageView]
    val stateDb: StateDB = new StateDB(db, hashNull)
    new AccountStateView(metadataStorageView, stateDb, messageProcessors)
  }

  @Test
  def testInit(): Unit = {
    val stateView = getView()

    ForgerStakeMsgProcessor.init(stateView)

    stateView.stateDb.close()

  }

  @Test
  def testCanProcess(): Unit = {
    val stateView = getView()

    val value : java.math.BigInteger = java.math.BigInteger.ONE
    val msg = new Message(null, ForgerStakeMsgProcessor.myAddress,value, value, value, value, value,value, new Array[Byte](0) )

    assertTrue(ForgerStakeMsgProcessor.canProcess(msg, stateView))

    val msgNotProcessable = new Message(null, new AddressProposition( BytesUtils.fromHexString("35fdd51e73221f467b40946c97791a3e19799bea")),value, value, value, value, value,value, new Array[Byte](0) )
    assertFalse(ForgerStakeMsgProcessor.canProcess(msgNotProcessable, stateView))

    stateView.stateDb.close()
  }

  def getRandomNonce() : BigInteger = {
    val codeHash = new Array[Byte](32)
    util.Random.nextBytes(codeHash)
    new java.math.BigInteger(codeHash)
  }

  @Test
  def testAddAndRemoveStake(): Unit = {

    val stateView = getView()

    // create private/public key pair
    val pair = Keys.createEcKeyPair

    // TODO check this does not work (creating an address proposition from private key)
    //val ownerPrivateKey = new PrivateKeySecp256k1(BytesUtils.fromHexString("1122334455667788112233445566778811223344556677881122334455667788")) // 32 bytes
    //val ownerAddressProposition2 = ownerPrivateKey.publicImage()

    val ownerAddressProposition = new AddressProposition(BytesUtils.fromHexString(Keys.getAddress(pair)))

    val dummyBigInteger: java.math.BigInteger = java.math.BigInteger.ONE
    val stakedAmount: java.math.BigInteger = new java.math.BigInteger("10000000000")
    val from : AddressProposition = new AddressProposition(BytesUtils.fromHexString("00aabbcc9900aabbcc9900aabbcc9900aabbcc99"))
    val consensusEpochNumber : consensus.ConsensusEpochNumber = consensus.ConsensusEpochNumber@@123

    // we have to call init beforehand
    assertFalse(stateView.accountExists(ForgerStakeMsgProcessor.myAddress.address()))

    ForgerStakeMsgProcessor.init(stateView)

    assertTrue(stateView.accountExists(ForgerStakeMsgProcessor.myAddress.address()))

    val cmdInput = AddNewStakeInput(
      new PublicKey25519Proposition(BytesUtils.fromHexString("1122334455667788112233445566778811223344556677881122334455667788")), // 32 bytes
      new VrfPublicKey(             BytesUtils.fromHexString("aabbccddeeff0099aabbccddeeff0099aabbccddeeff0099aabbccddeeff001234")), // 33 bytes
      ownerAddressProposition
    )
    val data: Array[Byte] = AddNewStakeInputSerializer.toBytes(cmdInput)

    val msg = new Message(
      from,
      ForgerStakeMsgProcessor.myAddress, // to
      dummyBigInteger, // gasPrice
      dummyBigInteger, // gasFeeCap
      dummyBigInteger, // gasTipCap
      dummyBigInteger, // gasLimit
      stakedAmount,
      getRandomNonce(), // nonce
      data)

    Mockito.when(stateView.metadataStorageView.getConsensusEpochNumber).thenReturn(Some(consensusEpochNumber))

    // positive case
    ForgerStakeMsgProcessor.process(msg, stateView) match {
      case res: ExecutionSucceeded =>
        assertTrue(res.hasReturnData)
        assertTrue(res.gasUsed() == ForgerStakeMsgProcessor.AddNewStakeGasPaidValue)
        println("This is the returned value: " + BytesUtils.toHexString(res.returnData()))

      case result => Assert.fail(s"Wrong result: $result")
    }

    // try processing a msg with the same stake, should fail
    ForgerStakeMsgProcessor.process(msg, stateView) match {
      case res: InvalidMessage =>
        println("This is the reason: " + res.getReason.toString)
      case result => Assert.fail(s"Wrong result: $result")
    }

    val msg2 = new Message(
      from,
      ForgerStakeMsgProcessor.myAddress, // to
      dummyBigInteger, // gasPrice
      dummyBigInteger, // gasFeeCap
      dummyBigInteger, // gasTipCap
      dummyBigInteger, // gasLimit
      stakedAmount,
      getRandomNonce(), // nonce
      data)

    // try processing a msg with different stake, should succeed
    ForgerStakeMsgProcessor.process(msg2, stateView) match {
      case res: InvalidMessage => Assert.fail(s"Wrong result: $res")
      case res: ExecutionFailed => Assert.fail(s"Wrong result: $res")
      case res: ExecutionSucceeded =>
        assertTrue(res.hasReturnData)
        assertTrue(res.gasUsed() == ForgerStakeMsgProcessor.AddNewStakeGasPaidValue)
        println("This is the returned value: " + BytesUtils.toHexString(res.returnData()))

    }

    // remove first stake id
    val stakeId = getStakeId(stateView, msg)
    val nonce3 = getRandomNonce()
    val msgToSign = getMessageToSign(stakeId, from.address(), nonce3.toByteArray)
    val msgSignatureData = Sign.signMessage(msgToSign, pair, true)
    val msgSignature = new SignatureSecp256k1(msgSignatureData)

    val removeCmdInput = RemoveStakeInput(stakeId, msgSignature)

    val data3: Array[Byte] = RemoveStakeInputSerializer.toBytes(removeCmdInput)

    val msg3 = new Message(
      from,
      ForgerStakeMsgProcessor.myAddress, // to
      dummyBigInteger, // gasPrice
      dummyBigInteger, // gasFeeCap
      dummyBigInteger, // gasTipCap
      dummyBigInteger, // gasLimit
      BigInteger.valueOf(-1),
      nonce3, // nonce
      data3)


    // try processing the removal of stake
    ForgerStakeMsgProcessor.process(msg3, stateView) match {
      case res: InvalidMessage => Assert.fail(s"Wrong result: $res")
      case res: ExecutionFailed => Assert.fail(s"Wrong result: $res")
      case res: ExecutionSucceeded =>
        assertTrue(res.hasReturnData)
        assertTrue(res.gasUsed() == ForgerStakeMsgProcessor.RemoveStakeGasPaidValue)
        println("This is the returned value: " + BytesUtils.toHexString(res.returnData()))

    }

    val data4: Array[Byte] = BytesUtils.fromHexString(GetListOfForgersCmd)

    val msg4 = new Message(
      from,
      ForgerStakeMsgProcessor.myAddress, // to
      dummyBigInteger, // gasPrice
      dummyBigInteger, // gasFeeCap
      dummyBigInteger, // gasTipCap
      dummyBigInteger, // gasLimit
      BigInteger.valueOf(-1),
      getRandomNonce(), // nonce
      data4)

    // try getting the list
    ForgerStakeMsgProcessor.process(msg4, stateView) match {
      case res: InvalidMessage => Assert.fail(s"Wrong result: $res")
      case res: ExecutionFailed => Assert.fail(s"Wrong result: $res")
      case res: ExecutionSucceeded =>
        assertTrue(res.hasReturnData)
        assertTrue(res.gasUsed() == ForgerStakeMsgProcessor.RemoveStakeGasPaidValue)

        val forgingInfoSerializer = new ListSerializer[AccountForgingStakeInfo](AccountForgingStakeInfoSerializer)
        val returnedList = forgingInfoSerializer.parseBytesTry(res.returnData()).get

        assertTrue(returnedList.size() == 1)
        val item = returnedList.get(0)
        assertTrue(BytesUtils.toHexString(item.stakeId) == BytesUtils.toHexString(getStakeId(stateView, msg2)))

        println("This is the returned value: " + item)

    }


    stateView.stateDb.close()
  }

}
