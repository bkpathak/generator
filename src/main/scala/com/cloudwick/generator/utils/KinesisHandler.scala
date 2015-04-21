package com.cloudwick.generator.utils

import java.nio.ByteBuffer

import io.github.cloudify.scala.aws.kinesis.Client
import io.github.cloudify.scala.aws.kinesis.Client.ImplicitExecution._
import io.github.cloudify.scala.aws.kinesis.Definitions.Stream
import io.github.cloudify.scala.aws.kinesis.KinesisDsl._
import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import io.github.cloudify.scala.aws.auth.CredentialsProvider.DefaultHomePropertiesFile
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{TimeoutException, Await}
import scala.concurrent.duration._

/**
 * Created by bijay on 4/17/15.
 */
class KinesisHandler(val streamName: String) {
  lazy val logger = LoggerFactory.getLogger(getClass)
  implicit val kinesisClient = Client.fromCredentials(DefaultHomePropertiesFile)
  val duration = 10
  private var stream: Option[Stream] = None


  def createStream(): Boolean = {
    logger.info("Checking is stream name already exists or not.")
    val streamListFuture = for {
      s <- Kinesis.streams.list
    } yield s
    val streamList: Iterable[String] = Await.result(streamListFuture, Duration(duration, SECONDS))
    for (stream <- streamList) {
      if (stream == streamName) {
        logger.info("Stream ${streamName} already exists.")
        return true
      }
    }
    logger.info("Stream ${streamName} doesn't exist, creating stream.")
    val createStream = for {
      s <- Kinesis.streams.create(streamName)
    } yield s

    try {
      stream = Some(Await.result(createStream, Duration(duration, SECONDS)))
      Await.result(stream.get.waitActive.retrying(duration),
        Duration(duration, SECONDS))
    } catch {
      case _: TimeoutException =>
        logger.debug("Error: Timed out.")
        return false
    }
    logger.info("Successfully created stream.")
    return true
  }


  def close() = {
    try {
      logger.debug("Attempting to close the kinesis stream")
      val deleteStream = for {
        _ <- stream.get.delete
      } yield ()
      Await.result(deleteStream, Duration(duration, SECONDS))
      logger.debug("$streamName Deleted")
    } catch {
      case _: Throwable => ()
    }
  }

  def writeRecord(data: String, timeStamp: Long) = {
    val stringKey = s"partition-key-${timeStamp % 100000}"
    val putData = stream.get.put(ByteBuffer.wrap(data.getBytes), stringKey)
    Await.result(putData, Duration(duration, SECONDS))
  }

  def publish(record: String) = {
  }

  def publishBuffered(records: ArrayBuffer[String]) = {
    try {
      records.foreach {
        record =>
          writeRecord(record, System.currentTimeMillis())
      }
    } catch {
      case e: Throwable => logger.error("Error:: {}", e)
    }

  }
}




