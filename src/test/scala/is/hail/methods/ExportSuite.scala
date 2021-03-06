package is.hail.methods

import is.hail.SparkSuite
import is.hail.utils._
import is.hail.driver._
import is.hail.io.vcf.LoadVCF
import org.testng.annotations.Test

import scala.io.Source

class ExportSuite extends SparkSuite {

  @Test def test() {
    val vds = LoadVCF(sc, "src/test/resources/sample.vcf")
    var state = State(sc, sqlContext, vds)
    state = SplitMulti.run(state, Array.empty[String])

    val sampleQCFile = tmpDir.createTempFile("sampleqc", ".tsv")
    val exportSamplesFile = tmpDir.createTempFile("exportsamples", ".tsv")

    SampleQC.run(state, Array("-o", sampleQCFile))
    val postSampleQC = SampleQC.run(state, Array.empty[String])

    val sb = new StringBuilder()
    sb.tsvAppend(Array(1, 2, 3, 4, 5))
    assert(sb.result() == "1,2,3,4,5")

    sb.clear()
    sb.tsvAppend(5.124)
    assert(sb.result() == "5.12400e+00")

    ExportSamples.run(postSampleQC, Array("-o", exportSamplesFile, "-c",
      "Sample=s.id, callRate=sa.qc.callRate,nCalled=sa.qc.nCalled,nNotCalled=sa.qc.nNotCalled,nHomRef=sa.qc.nHomRef," +
        "nHet=sa.qc.nHet,nHomVar=sa.qc.nHomVar,nSNP=sa.qc.nSNP,nInsertion=sa.qc.nInsertion," +
        "nDeletion=sa.qc.nDeletion,nSingleton=sa.qc.nSingleton,nTransition=sa.qc.nTransition," +
        "nTransversion=sa.qc.nTransversion,dpMean=sa.qc.dpMean,dpStDev=sa.qc.dpStDev," +
        "gqMean=sa.qc.gqMean,gqStDev=sa.qc.gqStDev," +
        "nNonRef=sa.qc.nNonRef," +
        "rTiTv=sa.qc.rTiTv,rHetHomVar=sa.qc.rHetHomVar," +
        "rInsertionDeletion=sa.qc.rInsertionDeletion"))

    val sQcOutput = hadoopConf.readFile(sampleQCFile) { s =>
      Source.fromInputStream(s)
        .getLines().toSet
    }
    val sExportOutput = hadoopConf.readFile(exportSamplesFile) { s =>
      Source.fromInputStream(s)
        .getLines().toSet
    }
    println(sExportOutput.toArray.sorted.toSeq)
    println(sQcOutput.toArray.sorted.toSeq)

    assert(sQcOutput == sExportOutput)
  }

  @Test def testExportSamples() {
    var s = State(sc, sqlContext)
    s = ImportVCF.run(s, Array("src/test/resources/sample.vcf"))
    s = SplitMulti.run(s, Array.empty[String])
    s = FilterSamplesExpr.run(s, Array("--keep", "-c", """s.id == "C469::HG02026""""))
    assert(s.vds.nSamples == 1)

    // verify exports localSamples
    val f = tmpDir.createTempFile("samples", ".tsv")
    s = ExportSamples.run(s, Array("-o", f, "-c", "s.id"))
    assert(sc.textFile(f).count() == 1)
  }

  @Test def testAllowedNames() {
    var s = State(sc, sqlContext)

    val f = tmpDir.createTempFile("samples", ".tsv")
    val f2 = tmpDir.createTempFile("samples", ".tsv")
    val f3 = tmpDir.createTempFile("samples", ".tsv")

    s = ImportVCF.run(s, Array("src/test/resources/sample.vcf"))
    s = SplitMulti.run(s, Array.empty[String])
    s = ExportSamples.run(s, Array("-o", f, "-c", "S.A.M.P.L.E.ID = s.id"))
    s = ExportSamples.run(s, Array("-o", f2, "-c",
      "$$$I_HEARD_YOU_LIKE_%%%_#@!_WEIRD_CHARS**** = s.id, ANOTHERTHING=s.id"))
    s = ExportSamples.run(s, Array("-o", f3, "-c",
      "`I have some spaces and tabs\\there` = s.id,`more weird stuff here`=s.id"))
    hadoopConf.readFile(f) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "S.A.M.P.L.E.ID")
    }
    hadoopConf.readFile(f2) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "$$$I_HEARD_YOU_LIKE_%%%_#@!_WEIRD_CHARS****\tANOTHERTHING")
    }
    hadoopConf.readFile(f3) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "I have some spaces and tabs\there\tmore weird stuff here")
    }
  }

  @Test def testIf() {
    var s = State(sc, sqlContext)
    s = ImportVCF.run(s, Array("src/test/resources/sample.vcf"))
    s = SplitMulti.run(s, Array.empty[String])
    s = SampleQC.run(s, Array.empty[String])

    // this should run without errors
    val f = tmpDir.createTempFile("samples", ".tsv")
    s = ExportSamples.run(s, Array("-o", f, "-c", "computation = 5 * (if (sa.qc.callRate < .95) 0 else 1)"))
  }

  @Test def testTypes() {
    var s = State(sc, sqlContext)
    s = ImportVCF.run(s, Array("src/test/resources/sample.vcf"))
    s = SplitMulti.run(s, Array.empty[String])
    val out = tmpDir.createTempFile("export", ".out")
    val types = tmpDir.createLocalTempFile("export", ".types")

    ExportVariants.run(s, Array("-o", out,
      "-t", types,
      "-c", "v = v, va = va"))

    val preVDS = s.vds

    s = AnnotateVariantsTable.run(s, Array(out,
      "-e", "v",
      "-r", "va.tmp",
      "-t", s"@${ uriPath(types) }"))
    s = AnnotateVariantsExpr.run(s, Array("-c", "va = va.tmp.va"))

    assert(s.vds.same(preVDS))
  }
}
