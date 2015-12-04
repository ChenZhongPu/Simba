package org.apache.spark.sql.partitioner

import org.apache.spark.rdd.{ShuffledRDD, RDD}
import org.apache.spark.shuffle.sort.SortShuffleManager
import org.apache.spark.sql.execution.SparkSqlSerializer
import org.apache.spark.util.{MutablePair, CollectionsUtils}
import org.apache.spark.{SparkConf, SparkEnv, Partitioner}
import org.apache.spark.sql.catalyst.InternalRow

import scala.reflect.ClassTag

/**
 * Created by Dong Xie on 9/28/15.
 * Range Partitioner with Determined Range Bounds
 */
object RangeDPartition {
  def sortBasedShuffleOn = SparkEnv.get.shuffleManager.isInstanceOf[SortShuffleManager]

  def apply[K: Ordering: ClassTag, T](origin: RDD[(K, (T, InternalRow))], range_bounds: Array[K]): RDD[(K, (T, InternalRow))] = {
    val rdd = if (sortBasedShuffleOn) {
      origin.mapPartitions {iter => iter.map(row => (row._1, (row._2._1, row._2._2.copy())))}
    } else {
      origin.mapPartitions {iter =>
        val mutablePair = new MutablePair[K, (T, InternalRow)]()
        iter.map(row => mutablePair.update(row._1, (row._2._1, row._2._2.copy())))
      }
    }

    val part = new RangeDPartitioner(range_bounds, ascending = true)
    val shuffled = new ShuffledRDD[K, (T, InternalRow), (T, InternalRow)](rdd, part)
    shuffled.setSerializer(new SparkSqlSerializer(new SparkConf(false)))
    shuffled
  }
}

class RangeDPartitioner[K: Ordering: ClassTag](range_bounds: Array[K], ascending: Boolean) extends Partitioner {
  def numPartitions = range_bounds.length + 1

  private val binarySearch: ((Array[K], K) => Int) = CollectionsUtils.makeBinarySearch[K]

  def getPartition(key: Any): Int = {
    val k = key.asInstanceOf[K]
    var partition = 0
    if (range_bounds.length < 128) {
      while (partition < range_bounds.length && Ordering[K].gt(k, range_bounds(partition)))
        partition += 1
    } else {
      partition = binarySearch(range_bounds, k)
      if (partition < 0) partition = -partition - 1
      if (partition > range_bounds.length) partition = range_bounds.length
    }
    if (ascending) partition
    else range_bounds.length - partition
  }
}