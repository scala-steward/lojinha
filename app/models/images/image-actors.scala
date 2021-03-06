package models.images

import java.io.File

import akka.actor._
import akka.routing.SmallestMailboxPool
import javax.inject.{ Inject, Singleton }
import models.aws.S3Sender
import play.api.Configuration

import scala.util.Random

@Singleton
class Images @Inject() (system: ActorSystem, configuration: Configuration) {

  val thumberRouter: ActorRef =
    system.actorOf(Props(new ImageThumberActor()).withRouter(SmallestMailboxPool(2)), "thumber-router")

  val s3SenderRouter: ActorRef =
    system.actorOf(Props(new S3SenderActor(configuration)).withRouter(SmallestMailboxPool(4)), "s3-sender-router")

  /** Generates a key for the image and returns it immediatelly, while sending the
    * image to be processed asynchronously with akka.
    */
  def processImage(image: File): String = {
    val validChars = "abcdefghijklmnopqwxyv_"
    val imageKey   = (1 to 20).foldLeft("")((t, a) => t + validChars(Random.nextInt(validChars.length)))
    thumberRouter ! GenThumb(image, imageKey)

    imageKey
  }

  def generateUrl(imageKey: String, thumbSize: ThumbSize): String =
    "https://s3.amazonaws.com/%s/%s".format(
      configuration.get[String]("aws.s3.bucket"), // fixme: replace get with getOptional
      Images.imageName(imageKey, thumbSize)
    )
}

object Images {
  def imageName(imageKey: String, thumbSize: ThumbSize): String = imageKey + thumbSize.suffix + ".png"
}

class ImageThumberActor extends Actor with ActorLogging {

  def receive = {
    case GenThumb(image, imageKey) =>
      log.info("about to generate thumbs for key {}", imageKey)

      val images         = new ImageThumber(image, imageKey).generateThumbs()
      val s3SenderRouter = context.system.actorSelection("akka://application/user/s3-sender-router")

      s3SenderRouter ! SendToS3(image, imageKey + ".png")
      images.foreach { imageTuple =>
        val (imageFile, imageName) = imageTuple
        s3SenderRouter ! SendToS3(imageFile, imageName)
      }
  }
}

class S3SenderActor(configuration: Configuration) extends Actor with ActorLogging {

  def receive = {
    case SendToS3(image, imageKey) =>
      log.info("about to send {} to s3", imageKey)
      new S3Sender(configuration)(image, imageKey).send()
  }
}

final case class GenThumb(image: File, imageKey: String)
final case class SendToS3(image: File, imageName: String)
