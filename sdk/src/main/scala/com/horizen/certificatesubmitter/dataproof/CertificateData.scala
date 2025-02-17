package com.horizen.certificatesubmitter.dataproof

import com.horizen.box.WithdrawalRequestBox
import com.horizen.proof.SchnorrProof
import com.horizen.proposition.SchnorrProposition

abstract class CertificateData(val referencedEpochNumber: Int,
                               val sidechainId: Array[Byte],
                               val withdrawalRequests: Seq[WithdrawalRequestBox],
                               val endEpochCumCommTreeHash: Array[Byte],
                               val btrFee: Long,
                               val ftMinAmount: Long,
                               val schnorrKeyPairs: Seq[(SchnorrProposition, Option[SchnorrProof])],
                                     ) {
  def getCustomFields: Seq[Array[Byte]]
}


