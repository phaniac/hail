package is.hail.io

import org.apache.spark.rdd.RDD
import is.hail.SparkSuite
import is.hail.utils._
import is.hail.annotations.Annotation
import is.hail.check.Gen
import is.hail.check.Prop._
import is.hail.driver._
import is.hail.expr.TStruct
import is.hail.io.vcf.LoadVCF
import is.hail.variant.{Genotype, VSMSubgen, Variant, VariantSampleMatrix}
import org.testng.annotations.Test

import scala.io.Source
import scala.language.postfixOps

class ExportVcfSuite extends SparkSuite {

  @Test def testSameAsOrigBGzip() {
    val vcfFile = "src/test/resources/multipleChromosomes.vcf"
    val outFile = tmpDir.createTempFile("export", ".vcf")

    val vdsOrig = LoadVCF(sc, vcfFile, nPartitions = Some(10))
    val stateOrig = State(sc, sqlContext, vdsOrig)

    ExportVCF.run(stateOrig, Array("-o", outFile))

    val vdsNew = LoadVCF(sc, outFile, nPartitions = Some(10))

    assert(vdsOrig.same(vdsNew, 1e-3))
  }

  @Test def testSameAsOrigNoCompression() {
    val vcfFile = "src/test/resources/multipleChromosomes.vcf"
    val outFile = tmpDir.createTempFile("export", ".vcf")
    val outFile2 = tmpDir.createTempFile("export2", ".vcf")

    val vdsOrig = LoadVCF(sc, vcfFile, nPartitions = Some(10))
    val stateOrig = State(sc, sqlContext, vdsOrig)

    ExportVCF.run(stateOrig, Array("-o", outFile))

    val vdsNew = LoadVCF(sc, outFile, nPartitions = Some(10))

    assert(vdsOrig.eraseSplit.same(vdsNew.eraseSplit))

    val infoSize = vdsNew.vaSignature.getAsOption[TStruct]("info").get.size
    val toAdd = Some(Annotation.fromSeq(Array.fill[Any](infoSize)(null)))
    val (_, inserter) = vdsNew.insertVA(null, "info")

    val vdsNewMissingInfo = vdsNew.mapAnnotations((v, va, gs) => inserter(va, toAdd))

    ExportVCF.run(stateOrig.copy(vds = vdsNewMissingInfo), Array("-o", outFile2))

    assert(LoadVCF(sc, outFile2).eraseSplit.same(vdsNewMissingInfo.eraseSplit, 1e-2))
  }

  @Test def testSorted() {
    val vcfFile = "src/test/resources/multipleChromosomes.vcf"
    val outFile = tmpDir.createTempFile("sort", ".vcf.bgz")

    val vdsOrig = LoadVCF(sc, vcfFile, nPartitions = Some(10))
    val stateOrig = State(sc, sqlContext, vdsOrig)
    println(vdsOrig.rdd.partitions.length)

    ExportVCF.run(stateOrig, Array("-o", outFile))

    val vdsNew = LoadVCF(sc, outFile, nPartitions = Some(10))
    val stateNew = State(sc, sqlContext, vdsNew)

    assert(hadoopConf.readFile(outFile) { s =>
      Source.fromInputStream(s)
        .getLines()
        .filter(line => !line.isEmpty && line(0) != '#')
        .map(line => line.split("\t")).take(5).map(a => Variant(a(0), a(1).toInt, a(3), a(4))).toArray
    }.isSorted)
  }

  @Test def testReadWrite() {
    val s = State(sc, sqlContext, null)
    val out = tmpDir.createTempFile("foo", ".vcf.bgz")
    val out2 = tmpDir.createTempFile("foo2", ".vcf.bgz")
    val p = forAll(VariantSampleMatrix.gen[Genotype](sc, VSMSubgen.random), Gen.choose(1, 10), Gen.choose(1, 10)) { case (vds, nPar1, nPar2) =>
      hadoopConf.delete(out, recursive = true)
      hadoopConf.delete(out2, recursive = true)
      ExportVCF.run(s.copy(vds = vds), Array("-o", out))
      val vsm2 = ImportVCF.run(s, Array(out, "-n", nPar1.toString)).vds
      ExportVCF.run(s.copy(vds = vsm2), Array("-o", out2))
      val vsm3 = ImportVCF.run(s, Array(out2, "-n", nPar1.toString)).vds

      vsm2.same(vsm3)
    }

    p.check()
  }

  @Test def testPPs() {
    var s = State(sc, sqlContext)
    s = ImportVCF.run(s, Array("src/test/resources/sample.PPs.vcf", "--pp-as-pl"))
    val out = tmpDir.createTempFile("exportPPs", ".vcf")
    ExportVCF.run(s, Array("-o", out, "--export-pp"))

    val vdsNew = LoadVCF(sc, out, nPartitions = Some(10), ppAsPL = true)

    assert(s.vds.same(vdsNew, 1e-3))
  }

  @Test def testGeneratedInfo() {
    var s = State(sc, sqlContext)
    s = ImportVCF.run(s, Array("src/test/resources/sample2.vcf"))

    s = AnnotateVariantsExpr.run(s, Array("-c", "va.info.AC = va.info.AC, va.info.another = 5"))

    val out = tmpDir.createTempFile("export", ".vcf")
    ExportVCF.run(s, Array("-o", out))

    hadoopConf.readFile(out) { in =>
      Source.fromInputStream(in)
        .getLines()
        .filter(_.startsWith("##INFO"))
        .foreach { line =>
          assert(line.contains("Description="))
        }
    }

  }
}