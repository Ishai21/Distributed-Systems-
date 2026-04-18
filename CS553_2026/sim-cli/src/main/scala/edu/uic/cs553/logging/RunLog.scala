package edu.uic.cs553.logging

import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory

import java.nio.file.Path

/** When `--out` is set, mirrors all SLF4J output to `run.log` in that directory (leader election lines, rollback, etc.). */
object RunLog:
  def install(logFile: Path): Unit =
    val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val root = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    if root.getAppender("RUN_LOG") != null then return ()

    val appender = new FileAppender[ILoggingEvent]()
    appender.setName("RUN_LOG")
    appender.setContext(lc)
    appender.setFile(logFile.toString)
    appender.setAppend(false)

    val enc = new PatternLayoutEncoder()
    enc.setContext(lc)
    enc.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{40} - %msg%n")
    enc.start()
    appender.setEncoder(enc)
    appender.start()

    root.setLevel(Level.INFO)
    root.addAppender(appender)
