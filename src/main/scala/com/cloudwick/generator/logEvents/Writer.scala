package com.cloudwick.generator.logEvents

import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import com.cloudwick.generator.utils._
import scala.collection.mutable.ArrayBuffer
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.Schema
import java.io.File

/**
 * Persists mocked events to specified file.
 * This class can persist events in both Text formats (csv, tsv) & also in Avro.
 * @author ashrith
 */
class Writer(eventsStartRange: Int,
             eventsEndRange: Int,
             counter: AtomicLong,
             sizeCounter: AtomicLong,
             config: OptionsConfig) extends Runnable {
  lazy val logger = LoggerFactory.getLogger(getClass)
  lazy val utils = new Utils
  lazy val ipGenerator = new IPGenerator(config.ipSessionCount, config.ipSessionLength)
  lazy val logEventGen = new LogGenerator(ipGenerator)
  lazy val schemaDesc = """
                          |{
                          | "type":"record",
                          | "name":"LogEvent",
                          | "fields":[
                          |   {"name":"OriginatingIp","type":"string"},
                          |   {"name":"ClientIdentity","type":"string"},
                          |   {"name":"UserID","type":"string"},
                          |   {"name":"TimeStamp","type":"string"},
                          |   {"name":"RequestType","type":"string"},
                          |   {"name":"RequestPage","type":"string"},
                          |   {"name":"HTTPProtocolVersion","type":"string"},
                          |   {"name":"ResponseCode","type":"int"},
                          |   {"name":"ResponseSize","type":"int"},
                          |   {"name":"Referrer","type":"string"},
                          |   {"name":"UserAgent","type":"string"}
                          |  ]
                          |}
                        """.stripMargin

  lazy val sleepTime = if (config.eventsPerSec == 0) 0 else 1000 / config.eventsPerSec

  def threadName = Thread.currentThread().getName

  def formatEventToString(logEvent: LogEvent) = {
    s"${logEvent.ip} - - [${logEvent.timestamp}]" + " \"GET " + logEvent.request + " HTTP/1.1\"" +
      s" ${logEvent.responseCode} ${logEvent.responseSize} " + "\"-\" \"" + logEvent.userAgent + "\"\n"
  }

  def avroEvent(event: LogEvent) = {
    val schema = new Schema.Parser().parse(schemaDesc)
    val datum: GenericRecord = new GenericData.Record(schema)
    datum.put("OriginatingIp", event.ip)
    datum.put("ClientIdentity", "-")
    datum.put("UserID", "-")
    datum.put("TimeStamp", event.timestamp)
    datum.put("RequestType", "GET")
    datum.put("RequestPage", event.request)
    datum.put("HTTPProtocolVersion", "HTTP/1.1")
    datum.put("ResponseCode", event.responseCode)
    datum.put("ResponseSize", event.responseSize)
    datum.put("Referrer", "-")
    datum.put("UserAgent", event.userAgent)
    datum
  }

  def run() = {
    val totalEvents = eventsEndRange - eventsStartRange + 1
    var batchCount: Int = 0
    val outputFile = new File(config.filePath, s"mock_apache_$threadName.data").toString
    var fileHandlerText: FileHandler = null
    var fileHandlerAvro: AvroFileHandler = null
    var kafkaHandler: KafkaHandler = null
    var kafkaAvroHandler: KafkaAvroHandler = null
    var eventsAvro = new ArrayBuffer[GenericRecord](config.flushBatch)
    var eventsText = new ArrayBuffer[String](config.flushBatch)
    val ipGenerator = new IPGenerator(config.ipSessionCount, config.ipSessionLength)
    val logEventGenerator = new LogGenerator(ipGenerator)
    var logEvent: LogEvent = null
    var kinesisHandler: KinesisHandler = null

    logger.debug("Initializing thread: {}", threadName)

    try {
      if (config.destination == "file") {
        if (config.outputFormat == "avro") {
          fileHandlerAvro = new AvroFileHandler(outputFile, schemaDesc, config.fileRollSize)
          fileHandlerAvro.openFile()
        } else {
          fileHandlerText = new FileHandler(outputFile, config.fileRollSize)
          fileHandlerText.openFile()
        }
      }
      else if (config.destination == "kafka") {
        if (config.outputFormat == "avro") {
          kafkaAvroHandler = new KafkaAvroHandler(config.kafkaBrokerList, schemaDesc, config.kafkaTopicName)
        } else {
          kafkaHandler = new KafkaHandler(config.kafkaBrokerList, config.kafkaTopicName)
        }
      }
      else {
        kinesisHandler = new KinesisHandler(config.kinesisStreamName)
        kinesisHandler.createStream()
      }
      // Start generating
      (eventsStartRange to eventsEndRange).foreach { eventCount =>
        Thread.sleep(sleepTime)
        batchCount += 1
        logEvent = logEventGenerator.eventGenerate
        sizeCounter.getAndAdd(logEvent.toString.getBytes.length)
        if (config.outputFormat == "avro") {
          eventsAvro += avroEvent(logEvent)
        } else {
          eventsText += formatEventToString(logEvent)
        }
        counter.getAndIncrement
        if (batchCount == config.flushBatch || batchCount == totalEvents) {
          if (config.destination == "file") {
            if (config.outputFormat == "avro") {
              fileHandlerAvro.publishBuffered(eventsAvro)
              eventsAvro.clear()
            } else {
              fileHandlerText.publishBuffered(eventsText)
              eventsText.clear()
            }
          }
          else if (config.destination == "kafka") {
            if (config.outputFormat == "avro") {
              kafkaAvroHandler.publishBuffered(eventsAvro)
              eventsAvro.clear()
            } else {
              kafkaHandler.publishBuffered(eventsText)
              eventsText.clear()
            }
          }
          else {
            kinesisHandler.publishBuffered(eventsText)
            eventsText.clear()
          }
          batchCount = 0
        }
      }
      logger.debug(s"Events generated by $threadName is: $totalEvents from ($eventsStartRange) to ($eventsEndRange)")
    } catch {
      case e: Exception =>
        logger.error("Error:: {}", e)
    }
    finally {
      if (config.destination == "file") {
        if (config.outputFormat == "avro") {
          fileHandlerAvro.close()
        } else {
          fileHandlerText.close()
        }
      } else if (config.destination == "kafka") {
        if (config.outputFormat == "avro") {
          kafkaAvroHandler.close()
        } else {
          kafkaHandler.close()
        }
      }
      else {
        //kinesisHandler.close()
      }
    }
  }
}
