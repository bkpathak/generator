package com.cloudwick.generator.utils

import io.github.cloudify.scala.aws.kinesis.Client
import io.github.cloudify.scala.aws.auth.CredentialsProvider.DefaultHomePropertiesFile

/**
 * Created by bijay on 4/17/15.
 */
class KinesisHandler {
  implicit val kinesisClient = Client.fromCredentials(DefaultHomePropertiesFile)
}
