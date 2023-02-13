package com.horizen.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import com.fasterxml.jackson.annotation.JsonView
import com.horizen.api.http.JacksonSupport._
import com.horizen.api.http.SidechainWalletRestScheme._
import com.horizen.block.{SidechainBlock, SidechainBlockHeader}
import com.horizen.box.Box
import com.horizen.node._
import com.horizen.proposition.Proposition
import com.horizen.serialization.Views
import com.horizen.utils.BytesUtils
import com.horizen.SidechainTypes
import sparkz.core.settings.RESTApiSettings
import com.horizen.companion.SidechainSecretsCompanion
import com.horizen.chain.SidechainFeePaymentsInfo
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.{Failure, Success}


case class SidechainWalletApiRoute(override val settings: RESTApiSettings,
                                   sidechainNodeViewHolderRef: ActorRef,
                                   sidechainSecretsCompanion: SidechainSecretsCompanion)(implicit override val context: ActorRefFactory, override val ec: ExecutionContext)
  extends WalletBaseApiRoute[
    SidechainTypes#SCBT,
    SidechainBlockHeader,
    SidechainBlock,
    SidechainFeePaymentsInfo,
    NodeHistory,
    NodeState,
    NodeWallet,
    NodeMemoryPool,
    SidechainNodeView] (settings, sidechainNodeViewHolderRef,
    sidechainSecretsCompanion: SidechainSecretsCompanion) {


  //Please don't forget to add Auth for every new method of the wallet.
  override val route: Route = pathPrefix("wallet") {
    allBoxes ~ coinsBalance ~ balanceOfType ~ createPrivateKey25519 ~ createVrfSecret ~ allPublicKeys ~ importSecret ~ exportSecret ~ dumpSecrets ~ importSecrets
  }

  override implicit val tag: ClassTag[SidechainNodeView] = ClassTag[SidechainNodeView](classOf[SidechainNodeView])


  /**
    * Return all boxes, excluding those which ids are included in 'excludeBoxIds' list. Filter boxes of a given type
    */
  def allBoxes: Route = (post & path("allBoxes")) {
    withBasicAuth {
      _ => {
        entity(as[ReqAllBoxes]) { body =>
          withNodeView { sidechainNodeView =>
            val optBoxTypeClass = body.boxTypeClass
            val wallet = sidechainNodeView.getNodeWallet
            val idsOfBoxesToExclude = body.excludeBoxIds.getOrElse(List()).map(idHex => BytesUtils.fromHexString(idHex))
            if (optBoxTypeClass.isEmpty) {
              val closedBoxesJson = wallet.allBoxes(idsOfBoxesToExclude.asJava).asScala.toList
              ApiResponseUtil.toResponse(RespAllBoxes(closedBoxesJson))
            } else {
              getClassByBoxClassName(optBoxTypeClass.get) match {
                case Failure(exception) => SidechainApiError(exception)
                case Success(clazz) =>
                  val allClosedBoxesByType = wallet.boxesOfType(clazz, idsOfBoxesToExclude.asJava).asScala.toList
                  ApiResponseUtil.toResponse(RespAllBoxes(allClosedBoxesByType))
              }
            }
          }
        }
      }
    }
  }

  /**
    * Returns the balance of all types of coins boxes
    */
  def coinsBalance: Route = (post & path("coinsBalance")) {
    withBasicAuth {
      _ => {
        withNodeView { sidechainNodeView =>
          val wallet = sidechainNodeView.getNodeWallet
          val sumOfBalances: Long = wallet.allCoinsBoxesBalance()
          ApiResponseUtil.toResponse(RespBalance(sumOfBalances))
        }
      }
    }
  }

  /**
    * Returns the balance for given box type
    */
  def balanceOfType: Route = (post & path("balanceOfType")) {
    withBasicAuth {
      _ => {
        entity(as[ReqBalance]) { body =>
          withNodeView { sidechainNodeView =>
            val wallet = sidechainNodeView.getNodeWallet
            getClassByBoxClassName(body.boxType) match {
              case Failure(exception) => SidechainApiError(exception)
              case Success(clazz) =>
                val balance = wallet.boxesBalance(clazz)
                ApiResponseUtil.toResponse(RespBalance(balance))
            }
          }
        }
      }
    }
  }


  def getClassByBoxClassName(className: String): Try[java.lang.Class[_ <: SidechainTypes#SCB]] = {
    Try(Class.forName(className).asSubclass(classOf[SidechainTypes#SCB])) orElse
      Try(Class.forName("com.horizen.box." + className).asSubclass(classOf[SidechainTypes#SCB]))
  }
}

object SidechainWalletRestScheme {

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqAllBoxes(boxTypeClass: Option[String], excludeBoxIds: Option[Seq[String]])

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespAllBoxes(boxes: List[Box[Proposition]]) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqBalance(boxType: String)

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespBalance(balance: Long) extends SuccessResponse

}

@JsonView(Array(classOf[Views.Default]))
case class ImportSecretsDetail private (lineNumber: Int,
                                        description: String) {
  def getLineNumber: Int = {
    lineNumber
  }

  def getDescription: String = {
    description
  }
}
