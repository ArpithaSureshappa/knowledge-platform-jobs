package org.sunbird.job.certpublic.task

import java.io.File
import java.util
import com.typesafe.config.ConfigFactory
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.sunbird.incredible.StorageParams
import org.sunbird.incredible.pojos.exceptions.ServerException
import org.sunbird.incredible.processor.store.StorageService
import org.sunbird.job.certpublic.domain.Event
import org.sunbird.job.certpublic.functions.{PostProcessOutputMetaData,CertificateGeneratorFunction, CreateUserFeedFunction, NotificationMetaData, NotifierFunction, UserFeedMetaData}
import org.sunbird.job.connector.FlinkKafkaConnector
import org.sunbird.job.util.{FlinkUtil, HttpUtil}

class AssessmentPublicCertificateGeneratorStreamTask(config: CertificateGeneratorConfig, kafkaConnector: FlinkKafkaConnector, httpUtil: HttpUtil, storageService: StorageService) {

  def process(): Unit = {
   implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(config)
    //implicit val env: StreamExecutionEnvironment = StreamExecutionEnvironment.createLocalEnvironment()
    implicit val eventTypeInfo: TypeInformation[Event] = TypeExtractor.getForClass(classOf[Event])
    implicit val mapTypeInfo: TypeInformation[util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[util.Map[String, AnyRef]])
    implicit val stringTypeInfo: TypeInformation[String] = TypeExtractor.getForClass(classOf[String])
    implicit val notificationMetaTypeInfo: TypeInformation[NotificationMetaData] = TypeExtractor.getForClass(classOf[NotificationMetaData])
    implicit val userFeedMetaTypeInfo: TypeInformation[UserFeedMetaData] = TypeExtractor.getForClass(classOf[UserFeedMetaData])
    implicit val postProcessOutputMetaData: TypeInformation[PostProcessOutputMetaData] = TypeExtractor.getForClass(classOf[PostProcessOutputMetaData])

    val source = kafkaConnector.kafkaJobRequestSource[Event](config.kafkaInputTopic)

    val processStreamTask = env.addSource(source)
      .name(config.certificateGeneratorConsumer)
      .uid(config.certificateGeneratorConsumer).setParallelism(config.kafkaConsumerParallelism)
      .rebalance
      .keyBy(new CertificateGeneratorKeySelector)
      .process(new CertificateGeneratorFunction(config, httpUtil, storageService))
      .name("public-assessment-certificate-generator-utility")
      .uid("public-assessment-certificate-generator-utility")
      .setParallelism(config.parallelism)

     //  processStreamTask.getSideOutput(config.auditEventOutputTag)
    //    .addSink(kafkaConnector.kafkaStringSink(config.kafkaAuditEventTopic))
   //    .name(config.certificateGeneratorAuditProducer)
  //    .uid(config.certificateGeneratorAuditProducer)

  //  processStreamTask.getSideOutput(config.notifierOutputTag)
  //    .process(new NotifierFunction(config, httpUtil))
  //    .name("notifier")
   //   .uid("notifier")
   //   .setParallelism(config.notifierParallelism)

   //  processStreamTask.getSideOutput(config.userFeedOutputTag)
    //    .process(new CreateUserFeedFunction(config, httpUtil))
   //    .name("user-feed")
    //   .uid("user-feed")
   //   .setParallelism(config.userFeedParallelism)

      processStreamTask.getSideOutput(config.postProcessorOutputTag)
      .addSink(kafkaConnector.kafkaStringSink(config.kafkaPostProcessorEventTopic))
        .name(config.certificateGeneratorPostProcessorProducer)
       .uid(config.certificateGeneratorPostProcessorProducer)

    env.execute(config.jobName)
  }

}

// $COVERAGE-OFF$ Disabling scoverage as the below code can only be invoked within flink cluster
object AssessmentPublicCertificateGeneratorStreamTask {

  def main(args: Array[String]): Unit = {
    val configFilePath = Option(ParameterTool.fromArgs(args).get("config.file.path"))
    val config = configFilePath.map {
      path => ConfigFactory.parseFile(new File(path)).resolve()
    }.getOrElse(ConfigFactory.load("public-assessment-certificate-generator-utility.conf").withFallback(ConfigFactory.systemEnvironment()))
    val ccgConfig = new CertificateGeneratorConfig(config)
    val kafkaUtil = new FlinkKafkaConnector(ccgConfig)
    val httpUtil = new HttpUtil
    var storageParams: StorageParams = null

    storageParams = StorageParams(ccgConfig.storageType, ccgConfig.storageKey, ccgConfig.storageSecret, ccgConfig.containerName, Some(ccgConfig.storageEndpoint))
    //val storageParams: StorageParams = StorageParams(ccgConfig.storageType, ccgConfig.azureStorageKey, ccgConfig.azureStorageSecret, ccgConfig.containerName)
    val storageService: StorageService = new StorageService(storageParams)
    val task = new AssessmentPublicCertificateGeneratorStreamTask(ccgConfig, kafkaUtil, httpUtil, storageService)
    task.process()
  }
}

// $COVERAGE-ON$

class CertificateGeneratorKeySelector extends KeySelector[Event, String] {
  override def getKey(event: Event): String = Set(event.userId, event.courseId, event.batchId).mkString("_")
}