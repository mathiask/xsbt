/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt

	import xsbti.{Logger => _,_}
	import compiler._
	import inc._
	import java.io.File

object Compiler
{
	val DefaultMaxErrors = 100

	def allProblems(inc: Incomplete): Seq[Problem] =
		allProblems(inc :: Nil)
	def allProblems(incs: Seq[Incomplete]): Seq[Problem] =
		problems(Incomplete.allExceptions(incs).toSeq)
	def problems(es: Seq[Throwable]): Seq[Problem]  =
		es flatMap {
			case cf: xsbti.CompileFailed => cf.problems
			case _ => Nil
		}

	final class Inputs(val compilers: Compilers, val config: Options, val incSetup: IncSetup)
	final class Options(val classpath: Seq[File], val sources: Seq[File], val classesDirectory: File, val options: Seq[String], val javacOptions: Seq[String], val maxErrors: Int)
	final class IncSetup(val analysisMap: Map[File, Analysis], val cacheDirectory: File)
	final class Compilers(val scalac: AnalyzingCompiler, val javac: JavaCompiler)

	def inputs(classpath: Seq[File], sources: Seq[File], outputDirectory: File, options: Seq[String], javacOptions: Seq[String], maxErrors: Int)(implicit compilers: Compilers, log: Logger): Inputs =
	{
			import Path._
		val classesDirectory = outputDirectory / "classes"
		val cacheDirectory = outputDirectory / "cache"
		val augClasspath = classesDirectory.asFile +: classpath
		inputs(augClasspath, sources, classesDirectory, options, javacOptions, Map.empty, cacheDirectory, maxErrors)
	}
	def inputs(classpath: Seq[File], sources: Seq[File], classesDirectory: File, options: Seq[String], javacOptions: Seq[String], analysisMap: Map[File, Analysis], cacheDirectory: File, maxErrors: Int)(implicit compilers: Compilers, log: Logger): Inputs =
		new Inputs(
			compilers,
			new Options(classpath, sources, classesDirectory, options, javacOptions, maxErrors),
			new IncSetup(analysisMap, cacheDirectory)
		)

	def compilers(implicit app: AppConfiguration, log: Logger): Compilers =
	{
		val scalaProvider = app.provider.scalaProvider
		compilers(ScalaInstance(scalaProvider.version, scalaProvider.launcher))
	}

	def compilers(instance: ScalaInstance)(implicit app: AppConfiguration, log: Logger): Compilers =
		compilers(instance, ClasspathOptions.auto, None)

	def compilers(instance: ScalaInstance, javac: (Seq[String], Logger) => Int)(implicit app: AppConfiguration, log: Logger): Compilers =
		compilers(instance, ClasspathOptions.auto, javac)

	def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javaHome: Option[File])(implicit app: AppConfiguration, log: Logger): Compilers =
	{
		val javac = directOrFork(instance, cpOptions, javaHome)
		compilers(instance, cpOptions, javac)
	}
	def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javac: (Seq[String], Logger) => Int)(implicit app: AppConfiguration, log: Logger): Compilers =
	{
		val javaCompiler = JavaCompiler.fork(cpOptions, instance)(javac)
		compilers(instance, cpOptions, javaCompiler)
	}
	def compilers(instance: ScalaInstance, cpOptions: ClasspathOptions, javac: JavaCompiler)(implicit app: AppConfiguration, log: Logger): Compilers =
	{
		val scalac = scalaCompiler(instance, cpOptions)
		new Compilers(scalac, javac)
	}
	def scalaCompiler(instance: ScalaInstance, cpOptions: ClasspathOptions)(implicit app: AppConfiguration, log: Logger): AnalyzingCompiler =
	{
		val launcher = app.provider.scalaProvider.launcher
		val componentManager = new ComponentManager(launcher.globalLock, app.provider.components, log)
		new AnalyzingCompiler(instance, componentManager, cpOptions, log)
	}
	def directOrFork(instance: ScalaInstance, cpOptions: ClasspathOptions, javaHome: Option[File]): JavaCompiler =
		if(javaHome.isDefined)
			JavaCompiler.fork(cpOptions, instance)(forkJavac(javaHome))
		else
			JavaCompiler.directOrFork(cpOptions, instance)( forkJavac(None) )

	def forkJavac(javaHome: Option[File]): (Seq[String], Logger) => Int =
	{
		import Path._
		val exec = javaHome match { case None => "javac"; case Some(jh) => (jh / "bin" / "javac").absolutePath }
		(args: Seq[String], log: Logger) => {
			log.debug("Forking javac: " + exec + " " + args.mkString(" "))
			Process(exec, args) ! log
		}
	}

	def apply(in: Inputs, log: Logger): Analysis =
	{
			import in.compilers._
			import in.config._
			import in.incSetup._
		
		val agg = new build.AggressiveCompile(cacheDirectory)
		agg(scalac, javac, sources, classpath, classesDirectory, options, javacOptions, analysisMap, maxErrors)(log)
	}
}