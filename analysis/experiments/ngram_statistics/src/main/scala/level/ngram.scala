import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import scala.collection.mutable._
import org.apache.spark.RangePartitioner
import org.apache.spark.HashPartitioner


import NgramUtilities._
import StatUtilities._

object NgramLevel {

  def setPara(): Map[String, Any] = {
    Map("ngramLists" -> List(4),
        "numPartition" -> 300,
        "ifTakeSample" -> false,
        "sampelSize" -> 10,
        "level" -> List("documents", "sentences")
       )
  }


  def processing( docRdd: RDD[String], phenoRdd: RDD[String],
                  ngram: Int, level: String, numPartition: Int): Unit = {
    /** set parameters */
    val phenoLabel: Int = -1

    /** split ngram based on given ngram list */

    val ngrams: RDD[(Int, List[(String, Int)])] =
      docRdd.mapPartitions({
        iter: Iterator[String] => for (line <- iter) yield splitNgram(line, takeNgrams, ngram)
      }).cache()

    val ngramsDocRdd = ngrams.partitionBy(new RangePartitioner(numPartition, ngrams)).cache()
    // val ngramsDocRdd = ngrams.cache()//.partitionBy(new RangePartitioner(numPartition, ngrams)).cache()


    val numOfDoc: Long = ngramsDocRdd.count()

    /** get valid ngram information (join phenotype dictionary with doc ngrams) */
    val reverseNgramRdd: RDD[(String, (Int, Int))] = ngramsDocRdd.mapPartitions(revertNgram)

    val reversePhenoRdd: RDD[(String, (Int, Int))] = phenoRdd.map(x => (x, (phenoLabel, 0)))

    val reverseRdd: RDD[(String, (Int, Int))] = reverseNgramRdd.union(reversePhenoRdd).cache()

    val groupedRdd: RDD[(String, ListBuffer[(Int, Int)])] = groupByName(reverseRdd).cache()

    val matchingRdd: RDD[(String, ListBuffer[(Int, Int)])] = filterUseless(groupedRdd, phenoLabel).cache()

    /** calculation for ngram statistics, i.e., tf, atf. cidf */
    val idTFCountRdd: RDD[(Int, (String, Int))] =
      if (level == "sentences") matchingRdd.mapPartitions(x => flatSentLevel(x, phenoLabel)).cache()
      else matchingRdd.mapPartitions(x => flatDocLevel(x, phenoLabel)).cache()

    val groupedIdTFCountRdd: RDD[(Int, ListBuffer[(String, Int)])] = groupById(idTFCountRdd).cache()
    val pairwiseWithGroupedId: RDD[((String, String), (Int, Int), Int)] = groupedIdTFCountRdd.flatMap(x => buildPairs(x._1, x._2)).cache()

    pairwiseWithGroupedId.saveAsTextFile("result/pairwiseWithGroupedId")

    val pairwiseScoreWithGroupedId: RDD[((String, String), Double, Int)] = pairwiseWithGroupedId.mapPartitions(x => evaluatePhenptypesScore(x, 5.0)).cache()
    val pairwiseStat: RDD[((String, String), (Double, Int))] = groupByPair(pairwiseScoreWithGroupedId).cache()

    pairwiseStat.map(x => (x._1, x._2._1)).saveAsTextFile("result/pairwiseTotalScore")
    pairwiseStat.map(x => (x._1, x._2._1 / x._2._2)).saveAsTextFile("result/pairwiseAverageScore")

    val cidf: RDD[((String, String), Double)] = pairwiseStat.map(c => (c._1, math.log((1.0 * numOfDoc / c._2._2).toDouble)))
    val atf: RDD[((String, String), Double)] = calculateATF(pairwiseScoreWithGroupedId)
    val statScore: RDD[((String, String), Double)] = calculateScore(cidf, atf, numOfDoc)
    statScore.saveAsTextFile("result/statScore" + ngram)
  }

  def main(args: Array[String]): Unit = {
    /** Configure Spark. */
    val conf = new SparkConf().setAppName("Ngram")
                              .set("spark.broadcast.factory", "org.apache.spark.broadcast.TorrentBroadcastFactory")
                              .set("spark.io.compression.codec", "snappy")
                              .set("spark.broadcast.compress", "true")
                              .set("spark.akka.frameSize", "1024")
                              .set("spark.driver.maxResultSize", "8g")
                              .set("spark.shuffle.compress", "true")
                              .set("spark.rdd.compress", "true")
  val sc = new SparkContext(conf)

  /** Configure parameters. */
  val docPath: String = "data/all_in_one"
  val phenotypePath: String = "data/parsed_name"
  val parameters: Map[String, Any] = setPara()
  val ngramss: List[Int] = parameters("ngramLists").asInstanceOf[List[Int]]
  val numPartition: Int = parameters("numPartition").asInstanceOf[Int]
  val ifTakeSample: Boolean = parameters("ifTakeSample").asInstanceOf[Boolean]
  val sampleSize: Int = parameters("sampelSize").asInstanceOf[Int]
  val level: String = parameters("level").asInstanceOf[List[String]](0)

  /** read data to RDD. */
  val originDocRdd: RDD[String] = sc.textFile(docPath, numPartition)

  val docRdd: RDD[String] =
    if (!ifTakeSample) originDocRdd
    else sc.parallelize( originDocRdd.take(sampleSize), numPartition )

  val phenoRdd: RDD[String] = sc.textFile(phenotypePath, numPartition).mapPartitions({
      iter: Iterator[String] =>
        for (line <- iter) yield splitPheno(line)
    }).filter(x => x._2 <= 4).map(_._1)

  /** processing the data and save to file. */
  for (ngrams <- ngramss;
    statScore = processing(originDocRdd, phenoRdd, ngrams, level, numPartition)
  )

  sc.stop()
  }
}
