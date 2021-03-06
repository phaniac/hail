package is.hail.driver

import org.apache.spark.sql.Row
import is.hail.utils._
import is.hail.annotations.Annotation
import is.hail.expr._
import is.hail.sparkextras.OrderedRDD
import is.hail.utils.{TextTableOptions, TextTableReader}
import is.hail.variant._
import org.kohsuke.args4j.{Argument, Option => Args4jOption}

import scala.collection.JavaConverters._

object ImportAnnotations extends SuperCommand {
  def name = "importannotations"

  def description = "Import variants and annotations as a sites-only VDS"

  register(ImportAnnotationsTable)
}

object ImportAnnotationsTable extends Command with JoinAnnotator {

  class Options extends BaseOptions with TextTableOptions {
    @Argument(usage = "<files...>")
    var arguments: java.util.ArrayList[String] = new java.util.ArrayList[String]()

    @Args4jOption(required = true, name = "-e", aliases = Array("--variant-expr"),
      usage = "Specify an expression to construct a variant from the fields of the text table")
    var vExpr: String = _

    @Args4jOption(required = false, name = "-c", aliases = Array("--code"),
      usage = "Use annotation expressions select specific columns / groups")
    var code: String = _

    @Args4jOption(name = "-n", aliases = Array("--npartition"), usage = "Number of partitions")
    var nPartitions: java.lang.Integer = _
  }

  def newOptions = new Options

  def name = "importannotations table"

  def description = "Import variants and annotations from a delimited text file as a sites-only VDS"

  def requiresVDS = false

  def supportsMultiallelic = true

  def run(state: State, options: Options): State = {

    val files = state.hadoopConf.globAll(options.arguments.asScala)
    if (files.isEmpty)
      fatal("Arguments referred to no files")

    val (struct, rdd) =
      if (options.nPartitions != null) {
        if (options.nPartitions < 1)
          fatal("requested number of partitions in -n/--npartitions must be positive")
        TextTableReader.read(state.sc)(files, options.config, options.nPartitions)
      } else
        TextTableReader.read(state.sc)(files, options.config)

    val (finalType, fn): (Type, (Annotation, Option[Annotation]) => Annotation) = Option(options.code).map { code =>
      val ec = EvalContext(Map(
        "va" -> (0, TStruct.empty),
        "table" -> (1, struct)))
      buildInserter(code, TStruct.empty, ec, Annotation.VARIANT_HEAD)
    }.getOrElse((struct, (_: Annotation, anno: Option[Annotation]) => anno.orNull))

    val ec = EvalContext(struct.fields.map(f => (f.name, f.typ)): _*)
    val variantFn = Parser.parseTypedExpr[Variant](options.vExpr, ec)

    val keyedRDD = rdd.flatMap {
      _.map { a =>
        ec.setAll(a.asInstanceOf[Row].toSeq: _*)
        variantFn().map(v => (v, (fn(null, Some(a)), Iterable.empty[Genotype])))
      }.value
    }.toOrderedRDD

    val vds: VariantDataset = VariantSampleMatrix(VariantMetadata(Array.empty[String], IndexedSeq.empty[Annotation], Annotation.empty,
      TStruct.empty, finalType, TStruct.empty), keyedRDD)

    state.copy(vds = vds)
  }

}
