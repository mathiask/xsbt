/* sbt -- Simple Build Tool
 * Copyright 2009  Mark Harrah
 */

package sbt.test

import java.io.File
import java.nio.charset.Charset

import xsbt.IPC
import xsbt.test.{CommentHandler, FileCommands, ScriptRunner, TestScriptParser}

final class ScriptedTests(resourceBaseDirectory: File, bufferLog: Boolean, sbtVersion: String, defScalaVersion: String, buildScalaVersions: String) extends NotNull
{
	@deprecated def this(resourceBaseDirectory: File, bufferLog: Boolean, sbtVersion: String, defScalaVersion: String, level: CompatibilityLevel.Value) =
		this(resourceBaseDirectory, bufferLog, sbtVersion, defScalaVersion, CompatibilityLevel.defaultVersions(level))

	private val testResources = new Resources(resourceBaseDirectory)
	
	val ScriptFilename = "test"
	
	def scriptedTest(group: String, name: String, log: Logger): Option[String] =
		testResources.readWriteResourceDirectory(group, name, log) { testDirectory =>
			scriptedTest(group + " / " + name, testDirectory, log).toLeft(())
		}.left.toOption
	private def scriptedTest(label: String, testDirectory: File, log: Logger): Option[String] =
		IPC.pullServer( scriptedTest0(label, testDirectory, log) )
	private def scriptedTest0(label: String, testDirectory: File, log: Logger)(server: IPC.Server): Option[String] =
	{
		FillProperties(testDirectory, sbtVersion, defScalaVersion, buildScalaVersions)
		val buffered = new BufferedLogger(log)
		if(bufferLog)
			buffered.recordAll
		
		def createParser() =
		{
			val fileHandler = new FileCommands(testDirectory)
			val sbtHandler = new SbtHandler(testDirectory, buffered, server)
			new TestScriptParser(Map('$' -> fileHandler, '>' -> sbtHandler, '#' -> CommentHandler))
		}
		def runTest() =
		{
			val run = new ScriptRunner
			val parser = createParser()
			run(parser.parse(new File(testDirectory, ScriptFilename)))
		}

		try
		{
			runTest()
			buffered.info("+ " + label)
			None
		}
		catch
		{
			case e: xsbt.test.TestException =>
				buffered.playAll()
				buffered.error("x " + label)
				if(e.getCause eq null)
					buffered.error("   " + e.getMessage)
				else
					e.printStackTrace
				Some(e.toString)
			case e: Exception =>
				buffered.playAll()
				buffered.error("x " + label)
				throw e
		}
		finally { buffered.clearAll() }
	}
}

object CompatibilityLevel extends Enumeration
{
	val Full, Basic, Minimal, Minimal27, Minimal28 = Value

	def defaultVersions(level: Value) =
		level match
		{
			case Full =>  "2.7.2 2.7.3 2.7.5 2.7.7 2.8.0.Beta1 2.8.0.RC1 2.8.0.RC2 2.8.0-SNAPSHOT"
			case Basic =>  "2.7.7 2.7.2 2.8.0.RC2"
			case Minimal => "2.7.7 2.8.0.RC2"
			case Minimal27 => "2.7.7"
			case Minimal28 => "2.8.0.RC2"
		}
}
object FillProperties
{
	def apply(projectDirectory: File, sbtVersion: String, defScalaVersion: String, level: CompatibilityLevel.Value): Unit =
		apply(projectDirectory, sbtVersion, defScalaVersion, CompatibilityLevel.defaultVersions(level))
	def apply(projectDirectory: File, sbtVersion: String, defScalaVersion: String, buildScalaVersions: String): Unit =
	{
		import xsbt.Paths._
		fill(projectDirectory / "project" / "build.properties", sbtVersion, defScalaVersion, buildScalaVersions)
	}
	def fill(properties: File, sbtVersion: String, defScalaVersion: String, buildScalaVersions: String)
	{
		val toAppend = extraProperties(sbtVersion, defScalaVersion, buildScalaVersions)
		xsbt.OpenResource.fileWriter(Charset.forName("ISO-8859-1"), true)(properties) { _.write(toAppend) }
	}
	def extraProperties(sbtVersion: String, defScalaVersion: String, buildScalaVersions: String) = 
<x>
sbt.version={sbtVersion}
def.scala.version={defScalaVersion}
build.scala.versions={buildScalaVersions}
</x>.text
}