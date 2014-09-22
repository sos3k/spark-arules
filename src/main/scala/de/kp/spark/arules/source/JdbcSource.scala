package de.kp.spark.arules.source
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
* 
* This file is part of the Spark-ARULES project
* (https://github.com/skrusche63/spark-arules).
* 
* Spark-ARULES is free software: you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation, either version 3 of the License, or (at your option) any later
* version.
* 
* Spark-ARULES is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE. See the GNU General Public License for more details.
* You should have received a copy of the GNU General Public License along with
* Spark-ARULES. 
* 
* If not, see <http://www.gnu.org/licenses/>.
*/

import java.sql.{Connection,DriverManager,ResultSet}

import org.apache.spark.SparkContext
import org.apache.spark.rdd.{JdbcRDD,RDD}

import de.kp.spark.arules.Configuration
import de.kp.spark.arules.spec.FieldSpec

import scala.collection.mutable.HashMap

class JdbcSource(@transient sc:SparkContext) extends Source(sc) {

  private val MYSQL_DRIVER   = "com.mysql.jdbc.Driver"
  private val NUM_PARTITIONS = 1
   
  private val (url,database,user,password) = Configuration.mysql
  
  private val fieldspec = FieldSpec.get
  private val fields = fieldspec.map(kv => kv._2._1).toList
  
  override def connect(params:Map[String,Any]):RDD[(Int,Array[Int])] = {
    
    /* Retrieve site and query from params */
    val site = params("site").asInstanceOf[Int]
    val query = params("query").asInstanceOf[String]
    
    /*
     * Convert field specification into broadcast variable
     */
    val spec = sc.broadcast(FieldSpec.get)

    val dataset = readTable(site,query).map(data => {
      
      val site = data(spec.value("site")._1).asInstanceOf[String]
      val timestamp = data(spec.value("timestamp")._1).asInstanceOf[Long]

      val user = data(spec.value("user")._1).asInstanceOf[String] 
      val order = data(spec.value("order")._1).asInstanceOf[String]

      val item  = data(spec.value("item")._1).asInstanceOf[Int]
      
      (site,user,order,timestamp,item)
      
    })
    
    /*
     * Next we convert the dataset into the SPMF format. This requires to
     * group the dataset by 'order', sort items in ascending order and make
     * sure that no item appears more than once in a certain order.
     * 
     * Finally, we organize all items of an order into an array, repartition 
     * them to single partition and assign a unqiue transaction identifier.
     */
    val ids = dataset.groupBy(_._3).map(valu => {

      val sorted = valu._2.map(_._5).toList.distinct.sorted    
      sorted.toArray
    
    }).coalesce(1)

    val index = sc.parallelize(Range.Long(0,ids.count,1),ids.partitions.size)
    ids.zip(index).map(valu => (valu._2.toInt,valu._1)).cache()

  }
  
  private def readTable(site:Int,query:String):RDD[Map[String,Any]] = {
    /*
     * The value of 'site' is used as upper and lower bound for 
     * the range (key) variable of the database table
     */
    val result = new JdbcRDD(sc,() => getConnection(url,database,user,password),
      query,site,site,NUM_PARTITIONS,
      (rs:ResultSet) => getRow(rs)
    ).cache()

    result
    
  }
  
  /**
   * Convert database row into Map[String,Any] and restrict
   * to column names that are defined by the field spec
   */
  private def getRow(rs:ResultSet):Map[String,Any] = {
    val metadata = rs.getMetaData()
    val numCols  = metadata.getColumnCount()
    
    val row = HashMap.empty[String,Any]
    (1 to numCols).foreach(i => {
      
      val k = metadata.getColumnName(i)
      val v = rs.getObject(i)
      
      if (fields.isEmpty) {
        row += k -> v
        
      } else {        
        if (fields.contains(k)) row += k -> v
        
      }
      
    })

    row.toMap
    
  }
  
  private def getConnection(url:String,database:String,user:String,password:String):Connection = {

    /* Create MySQL connection */
	Class.forName(MYSQL_DRIVER).newInstance()	
	val endpoint = getEndpoint(url,database)
		
	/* Generate database connection */
	val	connection = DriverManager.getConnection(endpoint,user,password)
    connection
    
  }
  
  private def getEndpoint(url:String,database:String):String = {
		
	val endpoint = "jdbc:mysql://" + url + "/" + database
	endpoint
		
  }

}