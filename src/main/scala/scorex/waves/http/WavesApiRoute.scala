package scorex.waves.http

import javax.ws.rs.Path
import akka.http.scaladsl.server.Route
import com.wavesplatform.settings.RestAPISettings
import io.swagger.annotations._
import scorex.account.Account
import scorex.api.http._
import scorex.crypto.encode.Base58
import scorex.transaction.state.wallet.Payment
import scorex.transaction.{PaymentTransaction, TransactionOperations}
import scorex.utils.NTP
import scorex.wallet.Wallet
import scorex.waves.transaction.{ExternalPayment, SignedPayment}

@Path("/waves")
@Api(value = "waves")
@Deprecated
case class WavesApiRoute(settings: RestAPISettings, wallet: Wallet,  transactionModule: TransactionOperations) extends ApiRoute {

  override lazy val route = pathPrefix("waves") {
    externalPayment ~ signPayment ~ broadcastSignedPayment ~ payment ~ createdSignedPayment
  }

  @Deprecated
  @Path("/payment")
  @ApiOperation(value = "Send payment from wallet. Deprecated: use /assets/transfer instead",
    notes = "Send payment from wallet to another wallet. Each call sends new payment. Deprecated: use /assets/transfer instead",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.transaction.state.wallet.Payment",
      defaultValue = "{\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"sender\":\"senderId\",\n\t\"recipient\":\"recipientId\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def payment: Route = withAuth {
    (path("payment") & post) {
      json[Payment] { payment =>
        transactionModule.createPayment(payment, wallet).map { tx =>
          SignedPayment(tx.timestamp, tx.amount, tx.fee, tx.recipient,
            tx.sender, tx.sender.address, Base58.encode(tx.signature))
        }
      }
    }
  }

  @Deprecated
  @Path("/payment/signature")
  @ApiOperation(value = "Create payment signed by address from wallet",
    notes = "Create unique payment signed by address from wallet. Without broadcasting to network.",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.transaction.state.wallet.Payment",
      defaultValue = "{\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"sender\":\"senderId\",\n\t\"recipient\":\"recipientId\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def signPayment: Route = (post & path("payment" / "signature")) {
    json[Payment] { payment =>
      wallet
        .privateKeyAccount(payment.sender).toRight[ApiError](InvalidSender)
        .flatMap { sender =>
          PaymentTransaction
            .create(sender, new Account(payment.recipient), payment.amount, payment.fee, NTP.correctedTime())
            .left.map(ApiError.fromValidationError)
        }
        .map { t =>
          SignedPayment(t.timestamp, t.amount, t.fee, t.recipient, t.sender, t.sender.address, Base58.encode(t.signature))
        }
    }
  }

  @Deprecated
  @Path("/create-signed-payment")
  @ApiOperation(value = "Sign payment",
    notes = "Sign payment by provided wallet seed",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.http.UnsignedPayment"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with response or error")
  ))
  def createdSignedPayment: Route = post { path("create-signed-payment")  {
    json[UnsignedPayment] { payment =>
      val senderWalletSeed = Base58.decode(payment.senderWalletSeed).getOrElse(Array.empty)
      if (senderWalletSeed.isEmpty)
        InvalidSeed
      else {
        val senderAccount = Wallet.generateNewAccount(senderWalletSeed, payment.senderAddressNonce)
        val recipientAccount = new Account(payment.recipient)

        transactionModule
          .createSignedPayment(senderAccount, recipientAccount, payment.amount, payment.fee, payment.timestamp)
          .map { tx =>
            SignedPayment(tx.timestamp, tx.amount, tx.fee, tx.recipient, tx.sender,
              tx.sender.address, Base58.encode(tx.signature))
          }
      }
    }
  }}

  @Deprecated
  @Path("/external-payment")
  @ApiOperation(value = "Broadcast payment", notes = "Publish signed payment to the Blockchain", httpMethod = "POST", produces = "application/json", consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.transaction.ExternalPayment",
      defaultValue = "{\n\t\"timestamp\": 0,\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"senderPublicKey\":\"senderPubKey\",\n\t\"recipient\":\"recipientId\",\n\t\"signature\":\"sig\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def externalPayment: Route = (path("external-payment") & post) {
    json[ExternalPayment] { payment =>
      broadcastPayment(payment)
    }
  }

  @Deprecated()
  @Path("/broadcast-signed-payment")
  @ApiOperation(value = "Broadcast signed payment. Deprecated: use /assets/broadcast/transfer instead",
    notes = "Publish signed payment to the Blockchain. Deprecated: use /assets/broadcast/transfer instead",
    httpMethod = "POST", produces = "application/json", consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.transaction.SignedPayment",
      defaultValue = "{\n\t\"timestamp\": 0,\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"senderPublicKey\":\"senderPubKey\",\n\t\"senderAddress\":\"senderAddress\",\n\t\"recipient\":\"recipientId\",\n\t\"signature\":\"sig\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def broadcastSignedPayment: Route = (path("broadcast-signed-payment") & post) {
    json[SignedPayment] { payment =>
      transactionModule.broadcastPayment(payment)
    }
  }

  private def broadcastPayment(payment: ExternalPayment) = {
    val senderAccount = payment.senderPublicKey
    val signedPayment = SignedPayment(payment.timestamp, payment.amount, payment.fee, payment.recipient,
      payment.senderPublicKey, senderAccount.address, Base58.encode(payment.signature))

    transactionModule.broadcastPayment(signedPayment)
  }
}
