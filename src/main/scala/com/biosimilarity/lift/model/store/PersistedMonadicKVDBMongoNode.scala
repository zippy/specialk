// -*- mode: Scala;-*- 
// Filename:    PersistedMonadicKVDBNode.scala 
// Authors:     lgm                                                    
// Creation:    Thu Mar 22 09:24:10 2012 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.lift.model.store

import com.biosimilarity.lift.model.ApplicationDefaults
import com.biosimilarity.lift.model.store.xml._
import com.biosimilarity.lift.model.store.mongo._
import com.biosimilarity.lift.model.agent._
import com.biosimilarity.lift.model.msg._
import com.biosimilarity.lift.lib._
import com.biosimilarity.lift.lib.moniker._
import net.liftweb.amqp._

import scala.util.continuations._ 
import scala.concurrent.{Channel => Chan, _}
//import scala.concurrent.cpsops._
import scala.xml._
import scala.collection.mutable.Map
import scala.collection.mutable.MapProxy
import scala.collection.mutable.HashMap
import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Stack
import scala.collection.mutable.MutableList

import com.rabbitmq.client._

import org.prolog4j._

import com.mongodb.casbah.Imports._

//import org.json4s._
//import org.json4s.jackson.JsonMethods._
//import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}

import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.json.JettisonMappedXmlDriver

import biz.source_code.base64Coder.Base64Coder

import javax.xml.transform.OutputKeys

import java.util.UUID
import java.net.URI
import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.io.ObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream


trait PersistedMonadicKVDBMongoNodeScope[Namespace,Var,Tag,Value] 
extends MonadicKVDBNodeScope[Namespace,Var,Tag,Value] with Serializable {  
  //type PersistedKVDBNodeRequest = MonadicKVDBNodeScope[Namespace,Var,Tag,Value]#KVDBNodeRequest
  //type PersistedKVDBNodeResponse = MonadicKVDBNodeScope[Namespace,Var,Tag,Value]#KVDBNodeResponse
  
  type PersistedKVDBNodeRequest = Msgs.MDistributedTermSpaceRequest[Namespace,Var,Tag,Value]
  type PersistedKVDBNodeResponse = RsrcMsgs.RsrcResponse[Namespace,Var,Tag,Value]

  trait PersistenceScope
    extends ExcludedMiddleScope[mTT.GetRequest,mTT.GetRequest,mTT.Resource]
    with Serializable {

      // trait RetainInStore extends emT.RetentionPolicy
//       case object Store extends RetainInStore
//       case object CacheAndStore extends emT.RetainInCache with RetainInStore

      trait PersistenceManifest extends Serializable {
	//def db : MongoDB
	def storeUnitStr[Src,Label,Trgt]( cnxn : Cnxn[Src,Label,Trgt] ) : String
	def storeUnitStr : String
	def toFile( ptn : mTT.GetRequest ) : Option[File]
	def query( ptn : mTT.GetRequest ) : Option[String]
	def query( xmlCollStr : String, ptn : mTT.GetRequest ) : Option[String]
	def kquery( xmlCollStr : String, ptn : mTT.GetRequest ) : Option[String]
	
	def valueStorageType : String
	def continuationStorageType : String
	
	def labelToNS : Option[String => Namespace]
	def textToVar : Option[String => Var]
	def textToTag : Option[String => Tag]        
	
	def kvNameSpace : Namespace
	def kvKNameSpace : Namespace
	
	def compareNameSpace( ns1 : Namespace, ns2 : Namespace ) : Boolean

	// How is a GetRequest represented as a key in the store?
	def asStoreKey(
	  key : mTT.GetRequest
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual
	
	// How is a Resource represented as a value in the store?
	def asStoreValue(
	  rsrc : mTT.Resource
	) : CnxnCtxtLeaf[Namespace,Var,Tag] with Factual
	
	// How is a key-value record represented in the store?
	def asStoreRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual
	
	// How is a key-continuation record represented in the store?
	def asStoreKRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual
	
	// How is a CCL representation of a key-value record represented in
	// the cache?
	def asCacheValue(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Value    
	
	def asCacheValue(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  ttt : String => Tag,
	  value : DBObject
	) : Option[Value]
	
	// How is a CCL representation of a key-continuation record represented in
	// the cache?
	def asCacheK(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : mTT.Continuation = {
	  throw new Exception( "shouldn't be calling this version of asCacheK" )
	}
	
	def asCacheK(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  value : DBObject
	) : Option[mTT.Continuation] = {
	  throw new Exception( "shouldn't be calling this version of asCacheK" )
	}

	def asResource(
	  key : mTT.GetRequest, // must have the pattern to determine bindings
	  value : DBObject
	) : emT.PlaceInstance 
	
	def recordDeletionQueryTemplate : String = {
	  "delete node let $key := %RecordKeyConstraints% for $rcrd in collection( '%COLLNAME%' )//%RECORDTYPE% where deep-equal($key, $rcrd/*[1]) return $rcrd"
	}
      }
      
      trait PersistenceManifestTrampoline extends Serializable {
	def persistenceManifest : Option[PersistenceManifest] = None
	
	def storeUnitStr[Src,Label,Trgt](
	  cnxn : Cnxn[Src,Label,Trgt]
	) : Option[String] = {
	  for( pd <- persistenceManifest ) 
	  yield {
	    pd.storeUnitStr( cnxn )
	  }
	}
	
	def storeUnitStr : Option[String] = {
	  for( pd <- persistenceManifest ) 
	  yield {
	    pd.storeUnitStr
	  }
	}
	
	def query( ptn : mTT.GetRequest ) : Option[String] = {
	  for( pd <- persistenceManifest; qry <- pd.query( ptn ) ) 
	  yield {
	    qry
	  }
	}
	
	def query( xmlCollStr : String, ptn : mTT.GetRequest ) : Option[String] = {
	  for( pd <- persistenceManifest; qry <- pd.query( xmlCollStr, ptn ) ) 
	  yield {
	    qry
	  }
	}
	
	def kquery( xmlCollStr : String, ptn : mTT.GetRequest ) : Option[String] = {
	  for( pd <- persistenceManifest; qry <- pd.kquery( xmlCollStr, ptn ) ) 
	  yield {
	    qry
	  }
	}
	
	def labelToNS : Option[String => Namespace] = {
	  for( pd <- persistenceManifest; ltns <- pd.labelToNS ) 
	  yield {
	    ltns
	  }
	}
	def textToVar : Option[String => Var] = {
	  for( pd <- persistenceManifest; ttv <- pd.textToVar ) 
	  yield {
	    ttv
	  }
	}
	def textToTag : Option[String => Tag] = {
	  for( pd <- persistenceManifest; ttt <- pd.textToTag ) 
	  yield {
	    ttt
	  }
	}
	
	def kvNameSpace : Option[Namespace] = {
	  for( pd <- persistenceManifest )
	  yield { pd.kvNameSpace }
	}
	
	def kvKNameSpace : Option[Namespace] = {
	  for( pd <- persistenceManifest )
	  yield { pd.kvKNameSpace }
	}
	
	def compareNameSpace( ns1 : Namespace, ns2 : Namespace ) : Boolean = {
	  persistenceManifest match {
	    case Some( pd ) => pd.compareNameSpace( ns1, ns2 )
	    case _ => false
	  }
	}
	
	def asStoreKey(
	  key : mTT.GetRequest
	) : Option[CnxnCtxtLabel[Namespace,Var,Tag] with Factual] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asStoreKey( key ) }
	}
	
	def asStoreValue(
	  rsrc : mTT.Resource
	) : Option[CnxnCtxtLeaf[Namespace,Var,Tag] with Factual] = {
	  for( pd <- persistenceManifest ) 
	  yield { pd.asStoreValue( rsrc ) }
	}
	
	def asStoreRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : Option[CnxnCtxtLabel[Namespace,Var,Tag] with Factual] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asStoreRecord( key, value ) }
	}
	
	def asStoreKRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : Option[CnxnCtxtLabel[Namespace,Var,Tag] with Factual] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asStoreKRecord( key, value ) }
	}
	
	def asResource(
	  key : mTT.GetRequest, // must have the pattern to determine bindings
	  value : DBObject
	) : Option[emT.PlaceInstance] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asResource( key, value ) }
	}
	
	def asCacheValue(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Option[Value] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asCacheValue( ccl ) }
	}
	
	def asCacheValue(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  ttt : String => Tag,
	  value : DBObject
	) : Option[Value] = {
	  for(
	    pd <- persistenceManifest;
	    rsrc <- pd.asCacheValue( ltns, ttv, ttt, value )
	  )	yield { rsrc }
	}
	
	def asCacheK(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Option[mTT.Continuation] = {
	  for( pd <- persistenceManifest )
	  yield { pd.asCacheK( ccl ) }
	}
	
	def asCacheK(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  value : DBObject
	) : Option[mTT.Continuation] = {
	  for(
	    pd <- persistenceManifest;
	    rsrc <- pd.asCacheK( ltns, ttv, value )
	  )	yield { rsrc }
	}
      }      

      abstract class MongoDBManifest(
	//@transient override val db : MongoDB
      ) extends PersistenceManifest 
	       with PrologMgr
	       with CnxnConversions[Namespace,Var,Tag]
	       with CnxnXQuery[Namespace,Var,Tag]
	       with CnxnXML[Namespace,Var,Tag]
	       with CnxnCtxtInjector[Namespace,Var,Tag]
               with CnxnNSVarSTagStringDefaults[Namespace,Var,Tag]
               with JSONIfy[Namespace,Var,Tag]
	       with XMLIfy[Namespace,Var]
	       with Blobify
	       with UUIDOps
      {
	import CnxnConversionStringScope._
	// BUGBUG -- LGM: Why not just the identity?
	override def asStoreKey(
	  key : mTT.GetRequest
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual = {
	  key match {
	    case CnxnCtxtLeaf( Left( t ) ) =>
	      new CnxnCtxtLeaf[Namespace,Var,Tag](
		Left( t )	    
	      )
	    case CnxnCtxtLeaf( Right( v ) ) =>
	      new CnxnCtxtLeaf[Namespace,Var,Tag](
		Right( v )
	      )
	    case CnxnCtxtBranch( ns, facts ) =>
	      new CnxnCtxtBranch[Namespace,Var,Tag](
		ns,
		facts.map( asStoreKey )
	      )
	  }
	}
	
	def asStoreEntry(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	)(
	  nameSpace : Namespace
	): CnxnCtxtLabel[Namespace,Var,Tag] with Factual = {
	  new CnxnCtxtBranch[Namespace,Var,Tag](
	    nameSpace,
	    List(
	      new CnxnCtxtBranch[Namespace,Var,Tag](
		labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "key" ),
		List( asStoreKey( key ) )
	      ),
	      new CnxnCtxtBranch[Namespace,Var,Tag](
		labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "value" ),
		List( asStoreValue( value ) )
	      )
	    )
	  )
	}

	override def asStoreRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual = {
	  asStoreEntry( key, value )( kvNameSpace )
	}

	override def asStoreKRecord(
	  key : mTT.GetRequest,
	  value : mTT.Resource
	) : CnxnCtxtLabel[Namespace,Var,Tag] with Factual = {
	  //println( "in asStoreKRecord with kvKNameSpace = " + kvKNameSpace )
	  asStoreEntry( key, value )( kvKNameSpace )
	}

	override def asCacheValue(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Value    
	
	override def asCacheValue(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  ttt : String => Tag,
	  value : DBObject
	) : Option[Value] = {
	  //val ttt = ( x : String ) => x
	  CnxnMongoObjectifier.fromMongoObject( value )( ltns, ttv, ttt ) match {
	    case CnxnCtxtBranch( ns, k :: v :: Nil ) => {
	      val vale : Value =
		asCacheValue(	      
		  v.asInstanceOf[CnxnCtxtLabel[Namespace,Var,Tag]]
		)
	      if ( kvNameSpace.equals( ns ) ) {	    
		Some( vale )
	      }
	      else {	    
		None
	      }
	    }
	    case v@_ => {
	      None
	    }
	  }
	}    
	
	override def asCacheK(
	  ccl : CnxnCtxtLabel[Namespace,Var,Tag]
	) : mTT.Continuation = {
	  ccl match {
	    case CnxnCtxtBranch(
	      "string",
	      CnxnCtxtLeaf( Left( rv ) ) :: Nil
	    ) => {
	      val unBlob =
		continuationStorageType match {
		  case "CnxnCtxtLabel" => {
		    fromXQSafeJSONBlob( tagToString( rv ) )
		  }
		  case "XStream" => {
		    fromXQSafeJSONBlob( tagToString( rv ) )
		  }
		  case "Base64" => {
		    val data : Array[Byte] = Base64Coder.decode( tagToString( rv ) )
		    val ois : ObjectInputStream =
		      new ObjectInputStream( new ByteArrayInputStream(  data ) )
		    val o : java.lang.Object = ois.readObject();
		    ois.close()
		    o
		  }
		}	      
	      
	      unBlob match {
		case k : mTT.Resource => {
		  k.asInstanceOf[mTT.Continuation]
		}
		case _ => {
		  throw new Exception(
		    (
		      ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
		      + "ill-formatted continuation stack blob : " + rv
		      + "\n" 
		      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
		      + "\n"
		      + "unBlob : " + unBlob
		      + "\n"
		      + "unBlob type : " + unBlob
		      + "\n"
		      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
		    )
		  )
		}
	      }
	    }
	    case _ => {
	      throw new Exception( "ill-formatted continuation stack leaf: " + ccl )
	    }
	  }
	}
	
	override def asCacheK(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  value : DBObject
	) : Option[mTT.Continuation] = {
	  throw new Exception( "shouldn't be calling this version of asCacheK" )
	}		
	
	override def query(
	  ptn : mTT.GetRequest
	) : Option[String] = {
	  for(
	    ttv <- textToVar
	  )
	  yield {
	    val qryCCL : CnxnCtxtLabel[Namespace,Var,Tag] =
	      new CnxnCtxtBranch[Namespace,Var,Tag](
		kvNameSpace,
		List(
		  new CnxnCtxtBranch[Namespace,Var,Tag](
		    labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "key" ),
		    List( asCCL( ptn ) )
		  ),
		  new CnxnCtxtBranch[Namespace,Var,Tag](
		    labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "value" ),
		    List(
		      new CnxnCtxtLeaf[Namespace,Var,Tag](
			Right(
			  ttv( "VisForValueVariableUniqueness" )
			)
		      )
		    )
		  )
		)
	      )
	    CnxnMongoQuerifier.toMongoQuery( qryCCL )(
	      nameSpaceToString, varToString, tagToString
	    ).toString
	  }
	}
	
	def queryRsrc(
	  xmlCollStr : String,
	  ptn : mTT.GetRequest
	)(
	  nameSpace : Namespace
	): Option[String] = {
	  for( ttv <- textToVar )
	  yield {
	    val ccb =
	      new CnxnCtxtBranch[Namespace,Var,Tag](
		nameSpace,
		List(
		  new CnxnCtxtBranch[Namespace,Var,Tag](
		    labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "key" ),
		    List( asCCL( ptn ) )
		  ),
		  new CnxnCtxtBranch[Namespace,Var,Tag](
		    labelToNS.getOrElse( throw new Exception( "missing labelToNS" ) )( "value" ),
		    List(
		      new CnxnCtxtLeaf[Namespace,Var,Tag](
			Right(
			  ttv( "VisForValueVariableUniqueness" )
			)
		      )
		    )
		  )
		)
	      )
	    val debugString = CnxnMongoQuerifier.toMongoQuery( ccb )(
	      nameSpaceToString, varToString, tagToString
	    ).toString
      debugString
	  }
	}
	
	override def query(
	  xmlCollStr : String,
	  ptn : mTT.GetRequest
	) : Option[String] = {
	  queryRsrc( xmlCollStr, ptn )( kvNameSpace )
	}
	
	override def kquery(
	  xmlCollStr : String,
	  ptn : mTT.GetRequest
	) : Option[String] = {
	  queryRsrc( xmlCollStr, ptn )( kvKNameSpace )
	}
	
	override def toFile(
	  ptn : mTT.GetRequest
	) : Option[File] = {
	  // TBD
	  None
	}    	
      }

      /*
      object MongoDBManifest {
	def unapply(
	  ed : MongoDBManifest
	) : Option[( MongoDB )] = {
	  Some( ( ed.db ) )
	}
      }
      */

      abstract class AbstractPersistedMonadicKVDB[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse, +Node[Rq <: ReqBody,Rs <: RspBody] <: AbstractPersistedMonadicKVDBNode[Rq,Rs,Node]](
	override val name : Moniker
      ) extends AbstractMonadicKVDB[ReqBody,RspBody,Node](
	name
      ) with PersistenceManifestTrampoline
	       with MongoDBStore[Namespace,Var,Tag]
	       with MongoCnxnStorage[Namespace,Var,Tag]
	       with Serializable
      {    
      }
  
      abstract class AbstractPersistedMonadicKVDBNode[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse, +Node[Rq <: ReqBody,Rs <: RspBody] <: AbstractPersistedMonadicKVDBNode[Rq,Rs,Node]](
	override val localCache : AbstractPersistedMonadicKVDB[ReqBody,RspBody,Node],
	override val acquaintances : List[Moniker]
      ) extends AbstractMonadicKVDBNode[ReqBody,RspBody,Node](
	localCache, acquaintances
      ) with MonadicTermStoreT
	       with Serializable {
	self : MessageFraming[String,ReqBody,RspBody] =>
	import identityConversions._			 
      }
      
      class BasePersistedMonadicKVDB[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse, +KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]](
	override val name : Moniker
      ) extends BaseMonadicKVDB[ReqBody,RspBody,KVDBNode](
	name
      ) with PersistenceManifestTrampoline
	       with MongoDBStore[Namespace,Var,Tag]
	       with MongoCnxnStorage[Namespace,Var,Tag]
               with CnxnNSVarSTagStringDefaults[Namespace,Var,Tag]
	       with Serializable 
      {	 		
	import LogConfiguration._ 
	override def tmpDirStr : String = {
	  val tds = config.getString( "storageDir", "tmp" )       
	  val tmpDir = new java.io.File( tds )
	  if ( ! tmpDir.exists ) {
	    tmpDir.mkdir
	  }
	  tds
	} 		
	
	override def asCacheValue(
	  ltns : String => Namespace,
	  ttv : String => Var,
	  ttt : String => Tag,
	  value : DBObject
	) : Option[Value] = {      
	  tweet(
	    "converting store value to cache value"
	  )
	  valueStorageType match {
	    case "CnxnCtxtLabel" => {
	      tweet(
		"using CnxnCtxtLabel method"
	      )	      
	      CnxnMongoObjectifier.fromMongoObject( value )( ltns, ttv, ttt ) match {
		case CnxnCtxtBranch( ns, k :: v :: Nil ) => {
		  tweet(
		    "Good news! Value has the shape of a record"
		  )
		  if ( kvNameSpace.getOrElse( "" ).equals( ns ) ) {
		    tweet(
		      "namespace matches : " + ns
		    )
		    tweet(
		      "value before conversion is \n" + v
		    )
		    for(
		      vale <-
		      asCacheValue(	      
			v.asInstanceOf[CnxnCtxtLabel[Namespace,Var,Tag]]
		      )		
		    ) yield { vale }
		  }
		  else {
		    if ( kvKNameSpace.getOrElse( "" ).equals( ns ) ) {
		      tweet(
			"namespace matches : " + ns
		      )
		      tweet(
			"value before conversion is \n" + v
		      )
		      for(
			vale <-
			asCacheValue(	      
			  v.asInstanceOf[CnxnCtxtLabel[Namespace,Var,Tag]]
			)		
		      ) yield { vale }
		    } else {
		      tweet(
			(
			  "namespace mismatch: "
			  + "kvNameSpace : " + kvNameSpace
			  + "kvKNameSpace : " + kvKNameSpace
			  + "," + ns
			)
		      )
		      None
		    }
		  }
		}
		case _ => {
		  tweet(
		    "Value failed to embody the shape of a record" + value
		  )
		  None
		}
	      }
	    }
	    case "XStream" => {
	      tweet(
		"using XStream method"
	      )
	      CnxnMongoObjectifier.fromMongoObject( value )( ltns, ttv, ttt ) match {
		case CnxnCtxtBranch( ns, k :: v :: Nil ) => {
		  v match {
		    case CnxnCtxtLeaf( Left( t ) ) => {
		      Some(
			new XStream(
			  new JettisonMappedXmlDriver
			).fromXML( tagToString( t ) ).asInstanceOf[Value]
		      )
		    }
		    case _ => None
		  }	      
		}
		case v@_ => {
		  None
		}
	      }	  
	    }
	    case _ => {
	      throw new Exception( "unexpected value storage type" )
	    }
	  }      
	}    
	
	implicit val SyncTable : Option[( UUID, HashMap[UUID,Int] )] = None

	def putInStore(
	  persist : Option[PersistenceManifest],
	  channels : Map[mTT.GetRequest,mTT.Resource],
	  ptn : mTT.GetRequest,
	  wtr : Option[mTT.GetRequest],
	  rsrc : mTT.Resource,
	  collName : Option[String],
	  spawnDBCall : Boolean
	)( implicit syncTable : Option[( UUID, HashMap[UUID,Int] )] ) : Unit = {
	  persist match {
	    case None => {
	      channels( wtr.getOrElse( ptn ) ) = rsrc	  
	    }
	    case Some( pd ) => {
	      val dbAccessExpr =
		() => {
		  for(
		    rcrd <- asStoreRecord( ptn, rsrc );
		    sus <- collName
		  ) {
		    tweet(
		      (
			"storing to db : " //+ pd.db
			+ " pair : " + rcrd
			+ " in coll : " + sus
		      )
		    )
		    store( sus )( rcrd )(
		      nameSpaceToString, varToString, tagToString
		    )
		  }
		}

	      tweet( "accessing db : " /* + pd.db */ )
	      // remove this line to force to db on get
	      channels( wtr.getOrElse( ptn ) ) = rsrc	  
	      if ( spawnDBCall ) {
		spawn {
		  dbAccessExpr()
		}
	      }
	      else {
		dbAccessExpr()
	      }
	    }
	  }
	}
	
	// BUGBUG -- lgm : how can we refactor out commonality between
	// this and the method, putInStore, above
	def putKInStore(
	  persist : Option[PersistenceManifest],
	  ptn : mTT.GetRequest,
	  rsrc : mTT.Resource,
	  collName : Option[String],
	  spawnDBCall : Boolean
	)( implicit syncTable : Option[( UUID, HashMap[UUID,Int] )] ) : Unit = {
	  persist match {
	    case None => {
	      // Nothing to do
	      tweet( "warning : no store in which to put continuation " + rsrc )
	    }
	    case Some( pd ) => {
	      tweet( "putKInStore accessing db : " /* + pd.db */ )
	      val dbAccessExpr =
		() => {
		  for(
		    rcrd <- asStoreKRecord( ptn, rsrc );
		    sus <- collName
		  ) {
		    tweet(
		      (
			"storing to db : " /* + pd.db */
			+ " pair : " + rcrd
			+ " in coll : " + sus
		      )
		    )
		    store( sus )( rcrd )(
		      nameSpaceToString, varToString, tagToString
		    )
		    for( ( sky, stbl ) <- syncTable ) {
		      stbl( sky ) = stbl( sky ) - 1
		    }
		  }
		}
	      if ( spawnDBCall ) {
		spawn {
		  dbAccessExpr() 
		}
	      }
	      else {
		dbAccessExpr() 
	      }
	    }
	  }
	}

	def pullKRecords(
	  persist : Option[PersistenceManifest],
	  path : CnxnCtxtLabel[Namespace,Var,Tag],
	  collName : Option[String]
	) : Option[List[emT.PlaceInstance]] = {
	  tweet( "#############>>>>>>***>>>>>>***>>>>>>#############" )
	  tweet( "pulling krecords" )
	  tweet( "#############>>>>>>***>>>>>>***>>>>>>#############" )
	  val xmlCollName =
	    collName.getOrElse( storeUnitStr.getOrElse( bail( ) ) )

	  for(
	    pd <- persist;	    
	    kqry <- kquery( xmlCollName, path );
	    if checkIfDBExists( xmlCollName, true )
	  ) yield {
	    for( krslt <- executeWithResults( xmlCollName, kqry ) ) yield {
	      tweet( ">>>>>>***>>>>>>***>>>>>>" )
	      tweet( "retrieved " + krslt.toString )
	      tweet( "<<<<<<***<<<<<<***<<<<<<" )
	      val ekrsrc = pd.asResource( path, krslt )
	      
	      tweet( ">>>>>>***>>>>>>***>>>>>>" )
	      tweet( "retrieved " + ekrsrc )
	      tweet( "<<<<<<***<<<<<<***<<<<<<" )
	      
	      tweet( "#############>>>>>>***>>>>>>***>>>>>>#############" )
	      tweet(
		"removing " + krslt + "\n"
		+ "from " + xmlCollName + "\n"
	      )
	      tweet( "#############>>>>>>***>>>>>>***>>>>>>#############" )
	      removeFromStore( persist, krslt, collName )
	      
	      ekrsrc
	    }
	  }
	}
	
	def updateKStore( persist : Option[PersistenceManifest] )( 
	  ptn : mTT.GetRequest,
	  consume : Boolean,
	  collName : Option[String]
	) : Option[List[emT.PlaceInstance]] = {
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : updateKStore "
	      + "\nthis : " + this
	      + "\nptn : " + ptn
	      + "\nconsume : " + consume
	      + "\ncollName : " + collName
	    )
	  )
	  val xmlCollName =
	    collName.getOrElse(
	      storeUnitStr.getOrElse(
		bail()
	      )
	    )
	  
	  checkIfDBExistsAndCreateIfNot( xmlCollName, true ) match {
	    case true => {
	      tweet( "database " + xmlCollName + " found" )
	      val oKQry = kquery( xmlCollName, ptn )
	      oKQry match {
		case None => {
		  throw new Exception(
		    "failed to compile a continuation query" 
		  )				  
		}
		case Some( kqry ) => {
		  tweet( "kqry : " + kqry )
		  val krslts = executeWithResults( xmlCollName, kqry )
		  krslts match {
		    case Nil => {
		      // Nothing to do
		      tweet( " no continuations in store " )
		      None
		    }
		    case _ => {
		      for( pm <- persist ) yield {
			for( krslt <- krslts ) yield {
			  tweet( "retrieved " + krslt.toString )
			  val ekrsrc = pm.asResource( ptn, krslt )
			  tweet( "krslt as resource " + ekrsrc )
			  ekrsrc.stuff match {
			    case Right( k :: ks ) => {
			      tweet( "have a list of continuations " )
			      if ( consume ) {
				// BUGBUG -- lgm : write XQuery to update node
				tweet( "removing from store " + krslt )
				removeFromStore( 
				  persist,
				  krslt,
				  collName
				)
				tweet( "updating store " )
				putKInStore(
				  persist,
				  ptn,
				  mTT.Continuation( ks ),
				  collName,
				  //true
				  false
				)
			      }
			      ekrsrc
			    }
			    case Right( Nil ) => {
			      tweet( " have empty list of continuations; no continuations in store " )
			      ekrsrc
			    }
			    case _ => {
			      throw new Exception(
				"Non-continuation resource (1) stored in kRecord" + ekrsrc
			      )
			    }
			  }
			}
		      }
		    }
		  }
		}
	      }
	    }
	    case false => {
	      tweet( "warning: failed to find a database!" )			  
	      None
	    }
	  }
	}
	
	def putPlaces( persist : Option[PersistenceManifest] )(
	  channels : Map[mTT.GetRequest,mTT.Resource],
	  registered : Map[mTT.GetRequest,List[RK]],
	  ptn : mTT.GetRequest,
	  rsrc : mTT.Resource,
	  consume : Boolean,
	  collName : Option[String]
	) : Generator[emT.PlaceInstance,Unit,Unit] = {    
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : putPlaces "
	      + "\nthis : " + this
	      + "\nchannels : " + channels
	      + "\nregistered : " + registered
	      + "\nptn : " + ptn
	      + "\nconsume : " + consume
	      + "\ncollName : " + collName
	    )
	  )
	  Generator {
	    k : ( emT.PlaceInstance => Unit @suspendable ) => 
	      // Are there outstanding waiters at this pattern?    
	      val map =
		Right[
		  Map[mTT.GetRequest,mTT.Resource],
		  Map[mTT.GetRequest,List[RK]]
		]( registered )
	    val waitlist = locations( map, ptn )
	    
	    waitlist match {
	      // Yes!
	      case waiter :: waiters => {
		tweet( "found waiters " + waitlist + " waiting for a value at " + ptn )
		val itr = waitlist.toList.iterator	    
		var nPI : emT.PlaceInstance = null
		while( itr.hasNext ) {
		  // BUGBUG -- lgm : SHOULD NOT HAVE TO CAST
		  nPI  = itr.next.asInstanceOf[emT.PlaceInstance]
		  tweet( "calling " + k + " on " + nPI )
		  k( nPI )
		}
	      }
	      // No...
	      case Nil => {
		// Store the rsrc at a representative of the ptn
		tweet( "in BasePersistedMonadicKVDB level putPlaces: no waiters waiting for a value at " + ptn )
		//channels( representative( ptn ) ) = rsrc

		updateKStore( persist )(
		  ptn, consume, collName
		) match {
		  case Some( pIs ) => {
		    for ( pI <- pIs ) {
		      pI.stuff match {
			case Right( k :: ks ) => {
			  for( sk <- ( k :: ks ) ) {
			    spawn {
			      sk( pI.subst( rsrc ) )
			    }
			  }
			}
			case Right( Nil ) => {
			  putInStore(
			    persist, channels, ptn, None, rsrc, collName, false //true
			  )
			}
		      }
		    }
		  }
		  case None => {
		    putInStore(
		      persist, channels, ptn, None, rsrc, collName, false //true
		    )
		  }
		}

		tweet( "Writer departing spaceLock on a PersistedMonadicKVDBNode for mput on " + ptn + "." )
		//spaceLock.depart( None )
		spaceLock.depart( ptn )
		//tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		//tweet( "spaceLock writing room: " + spaceLock.writingRoom )
		
	      }
	    }
	  }
	}
	
	def removeFromStore(
	  persist : Option[PersistenceManifest],
	  record : DBObject,
	  collName : Option[String]
	) : Unit = {
	  for( clNm <- collName ) {
	    deleteRecord( record )( clNm )
	  }
	}

	def mput( persist : Option[PersistenceManifest] )(
	  channels : Map[mTT.GetRequest,mTT.Resource],
	  registered : Map[mTT.GetRequest,List[RK]],
	  consume : Boolean,
	  collName : Option[String]
	)(
	  ptn : mTT.GetRequest,
	  rsrc : mTT.Resource
	) : Unit @suspendable = {
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : mput "
	      + "\nthis : " + this
	      + "\nchannels : " + channels
	      + "\nregistered : " + registered
	      + "\nconsume : " + consume
	      + "\ncollName : " + collName
	    )
	  )
	  //spaceLock.occupy( None )
	  spaceLock.occupy( ptn )
	  tweet( "Writer occupying spaceLock on a PersistedMonadicKVDBNode for mput on " + ptn + "." )
	  //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
	  //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

	  for(
	    placeNRKsNSubst
	    <- putPlaces(
	      persist
	    )( channels, registered, ptn, rsrc, consume, collName )
	  ) {	      
	    val emT.PlaceInstance( wtr, Right( rks ), s ) = placeNRKsNSubst
	    tweet( "waiters waiting for a value at " + wtr + " : " + rks )
	    updateKStore( persist )(
	      ptn, consume, collName
	    ) match {
	      case Some( pIs ) => {
		for ( pI <- pIs ) {
		  pI.stuff match {
		    case Right( k :: ks ) => {		      
		      tweet( "Writer departing spaceLock on a PersistedMonadicKVDBNode for mput on " + ptn + "." )
		      //spaceLock.depart( None )
		      spaceLock.depart( ptn )
		      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

		      for( sk <- ( k :: ks ) ) {			
			spawn {
			  sk( pI.subst( rsrc ) )
			}
		      }
		    }
		    case Right( Nil ) => {
		      putInStore(
			persist, channels, ptn, None, rsrc, collName, false //true
		      )

		      tweet( "Writer departing spaceLock on a PersistedMonadicKVDBNode for mput on " + ptn + "." )
		      //spaceLock.depart( None )
		      spaceLock.depart( ptn )
		      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )		      
		    }
		  }
		}
	      }
	      case None => {
		putInStore(
		  persist, channels, ptn, None, rsrc, collName, false //true
		)

		tweet( "Writer departing spaceLock on a PersistedMonadicKVDBNode for mput on " + ptn + "." )
		//spaceLock.depart( None )
		spaceLock.depart( ptn )
		//tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		//tweet( "spaceLock writing room: " + spaceLock.writingRoom )		
	      }
	    }            
	  }
	}
	
	def mget(
	  persist : Option[PersistenceManifest],
	  ask : dAT.AskNum,
	  hops : List[Moniker]
	)(
	  channels : Map[mTT.GetRequest,mTT.Resource],
	  registered : Map[mTT.GetRequest,List[RK]],
	  consume : RetentionPolicy,
	  keep : RetentionPolicy,
	  cursor : Boolean,
	  collName : Option[String]
	)(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	)
	: Generator[Option[mTT.Resource],Unit,Unit] = {
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : mget "
	      + "\nthis : " + this
	      + "\nchannels : " + channels
	      + "\nregistered : " + registered
	      + "\nconsume : " + consume
	      + "\nkeep : " + keep
	      + "\ncursor : " + cursor
	      + "\ncollName : " + collName
	      + "\npath : " + path
	      + "\n---------------------------------------"
	      + "\nchecking if this is a cache or a node: "
	      + (if ( this.isInstanceOf[BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode]] ) { "cache" } else { "node"}) + "\n"
	    )
	  )

	  def storeKQuery( xmlCollName : String, pd : PersistenceManifest )(
	    path : CnxnCtxtLabel[Namespace,Var,Tag],
	    rk : ( Option[mTT.Resource] => Unit @suspendable )
	  ) : Unit @suspendable = {
	    // Need to store the
	    // continuation on the tail of
	    // the continuation entry
	    val oKQry = kquery( xmlCollName, path )
	    oKQry match {
	      case None => {
		throw new Exception(
		  "failed to compile a continuation query" 
		)				  
	      }
	      case Some( kqry ) => {
		//tweet( ">>>>>>***>>>>>>***>>>>>>" )
		//tweet( "kqry: " + kqry )
		//println( "query text: " + kqry )
		//tweet( "<<<<<<***<<<<<<***<<<<<<" )
		val krslts = executeWithResults( xmlCollName, kqry )
		
		val stbl = new HashMap[UUID,Int]()
		val skey = getUUID		      
		
		// This is the easy case!
		// There are no locations
		// matching the pattern with
		// stored continuations	  					  
		krslts match {
		  case Nil => {
		    stbl += ( skey -> 1 )  	  
		    putKInStore(
		      persist,
		      path,
		      mTT.Continuation( List( rk ) ),
		      Some( xmlCollName ),
		      false
		      //true
		    )( Some( ( skey, stbl ) ) )
		    
		    while ( stbl( skey ) > 0 ){}

		    tweet(
		      (
			"Reader departing spaceLock PMKVDBNode Version 3 "
			+ this
			+ " on a PersistedMonadicKVDBNode for mget on "
			+ path + "."
		      )
		    )
		    //spaceLock.depart( Some( rk ) )
		    spaceLock.depart( path, Some( rk ) )
		    //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		    //tweet( "spaceLock writing room: " + spaceLock.writingRoom )					      
		    
		  }
		  case _ => {
		    // A more subtle
		    // case. Do we store
		    // the continutation on
		    // each match?
		    // Answer: Yes!
		    stbl += ( skey -> krslts.length )  	  
		    for( krslt <- itergen[DBObject]( krslts ) ) {
		      tweet( ">>>>>>***>>>>>>***>>>>>>" )
		      tweet( "retrieved " + krslt.toString )
		      tweet( "<<<<<<***<<<<<<***<<<<<<" )
		      val ekrsrc = pd.asResource( path, krslt )
		      
		      tweet( ">>>>>>***>>>>>>***>>>>>>" )
		      tweet( "retrieved " + ekrsrc )
		      tweet( "<<<<<<***<<<<<<***<<<<<<" )
		      
		      ekrsrc.stuff match {
			case Right( ks ) => {  
			  tweet( "removing from store " + krslt )
			  removeFromStore( 
			    persist,
			    krslt,
			    Some( xmlCollName )
			  )
			  putKInStore(
			    persist,
			    path,
			    mTT.Continuation( ks ++ List( rk ) ),
			    Some( xmlCollName ),
			    false //true
			  )( Some( ( skey, stbl ) ) )
			}
			case _ => {
			  throw new Exception(
			    "Non-continuation resource (2) stored in kRecord" + ekrsrc
			  )
			}
		      }
		    }
		    
		    while ( stbl( skey ) > 0 ){ }
		    tweet(
		      (
			"Reader departing spaceLock PMKVDBNode Version 3 "
			+ this
			+ " on a PersistedMonadicKVDBNode for mget on "
			+ path
			+ "."
		      )
		    )
		    //spaceLock.depart( Some( rk ) )
		    spaceLock.depart( path, Some( rk ) )
		    //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
		    //tweet( "spaceLock writing room: " + spaceLock.writingRoom )					      
		    
		  }
		}					      
	      }
	    }
	  }

	  Generator {	
	    rk : ( Option[mTT.Resource] => Unit @suspendable ) =>
	      shift {
		outerk : ( Unit => Unit ) =>
		  reset {
		    for(
		      oV <- mget( channels, registered, consume, keep, cursor )( path )( Some( rk ) )
		    ) {
		      oV match {
			case None => {
			  tweet( 
			    (
			      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			      + "mgetting " + path + ".\n"
			      + "on " + this + "did not find a result in memory cache.\n"
			      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			    )
			  )
			  persist match {
			    case None => {
			      tweet( 
				(
				  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				  + "mgetting " + path + ".\n"
				  + "on " + this + "without a persistence manifest.\n"
				  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				)
			      )

			      tweet( "Reader departing spaceLock PMKVDB Version 1" + this + " for mget on " + path + "." )
			      //spaceLock.depart( Some( rk ) )
			      spaceLock.depart( path, Some( rk ) )
			      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
			      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

			      rk( oV )
			    }
			    case Some( pd ) => {
			      tweet( 
				(
				  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				  + "mgetting " + path + ".\n"
				  + "on " + this + " with a persistence manifest.\n"
				  + "accessing db: " /* + pd.db */ + ".\n"
				  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				)
			      )
			      
			      val xmlCollName =
				collName.getOrElse(
				  storeUnitStr.getOrElse(
				    bail()
				  )
				)
			      
			      // Defensively check that db is actually available
			      
			      checkIfDBExistsAndCreateIfNot( xmlCollName, true ) match {
				case true => {
				  val oQry = query( xmlCollName, path )
				  
				  oQry match {
				    case None => {
				      tweet( "Reader departing spaceLock PMKVDBNode 2" + this + " for mget on " + path + "." )
				      //spaceLock.depart( Some( rk ) )
				      spaceLock.depart( path, Some( rk ) )
				      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
				      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

				      rk( oV )
				    }
				    case Some( qry ) => {	
				      tweet( ">>>>> compiled query for path " + path )
				      tweet(
					(
					  "retrieval query : \n" + qry
					)
				      )
				      
				      val rslts = executeWithResults( xmlCollName, qry )
				      
				      rslts match {
					case Nil => {	
					  tweet(
					    (
					      "database "
					      + xmlCollName
					      + " had no matching resources."
					    )
					  )
					  
					  storeKQuery( xmlCollName, pd )( path, rk )
					  
					  // Then forward the request
					  //forward( ask, hops, path )			  
					  rk( oV )
					}
					case _ => { 			  
					  tweet(
					    (
					      "database "
					      + xmlCollName
					      + " had "
					      + rslts.length
					      + " matching resources."
					    )
					  )		  				  
					  
					  if ( cursor )
					    {
                                              var rsrcRslts : List[mTT.Resource] = Nil
                                              for( rslt <- itergen[DBObject]( rslts ) ) {
						tweet( "retrieved " + rslt.toString )
												
						consume match {
						  case policy : RetainInStore => {
						    tweet( "removing from store " + rslt )
						    removeFromStore(
						      persist,
						      rslt,
						      collName
						    )
						  }
						  case _ => {
						    tweet( "policy indicates not to remove from store " + rslt )
						  }
						}
						
						// BUGBUG -- LGM : This is a
						// window of possible
						// failure; if we crash here,
						// then the result is out of
						// the store, but we haven't
						// completed processing. This is
						// where we need Tx.

						val ersrc : emT.PlaceInstance = pd.asResource( path, rslt )
						ersrc.stuff match {
						  case Left( r ) => rsrcRslts = r :: rsrcRslts
						  case _ => {}
						}
						
                                              }
					      
                                              val rsrcCursor = asCursor( rsrcRslts )
                                              //tweet( "returning cursor" + rsrcCursor )

					      tweet( "Reader departing spaceLock PMKVDBNode Version 4" + this + " for mget on " + path + "." )
					      //spaceLock.depart( Some( rk ) )
					      spaceLock.depart( path, Some( rk ) )
					      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
					      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )
					      
                                              rk( rsrcCursor )
					    }
					  else
					    {
					      for( rslt <- itergen[DBObject]( rslts ) ) {
						tweet( "retrieved " + rslt.toString )
						val ersrc = pd.asResource( path, rslt )
						
						consume match {
						  case policy : RetainInStore => {
						    tweet( "removing from store " + rslt )
						    removeFromStore( 
						      persist,
						      rslt,
						      collName
						    )
						  }
						  case _ => {
						    tweet( "policy indicates not to remove from store" + rslt )
						  }
						}
						
						// BUGBUG -- LGM : This is a
						// window of possible
						// failure; if we crash here,
						// then the result is out of
						// the store, but we haven't
						// completed processing. This is
						// where we need Tx.
						
						ersrc.stuff match {
						  case Left( r ) => {
						    tweet( "returning " + r )

						    tweet( "Reader departing spaceLock PMKVDBNode Version 5 " + this + " on a PersistedMonadicKVDBNode for mget on " + path + "." )
						    //spaceLock.depart( Some( rk ) )
						    spaceLock.depart( path, Some( rk ) )
						    //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
						    //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

						    rk( Some( r ) )
						  }
						  case _ => {
						    throw new Exception(
						      "violated excluded middle contract: " + ersrc
						    )
						  }
						}					  
					      }
					    }
					}
				      }
				    }			    
				  }			      
				}
				case false => {
				  // tweet( ">>>>> forwarding..." )
// 				  forward( ask, hops, path )

				  tweet( "Reader departing spaceLock PMKVDBNode Version 6 " + this + " for mget on " + path + "." )
				  //spaceLock.depart( Some( rk ) )
				  spaceLock.depart( path, Some( rk ) )
				  //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
				  //tweet( "spaceLock writing room: " + spaceLock.writingRoom )
				  
				  rk( oV )
				}
			      }
			    }		      
			  }
			}
			case oCacheV@Some( cacheV ) => {			  
			  tweet( 
			    (
			      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			      + "mgetting " + path + ".\n"
			      + "on " + this + "found a result in memory cache.\n"
			      + cacheV
			      + "\ncleaning store"
			      + "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			    )
			  )
			  persist match {
			    case None => {
			      tweet( 
				(
				  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				  + "mgetting " + path + ".\n"
				  + "result in cache on " + this + "without a persistence manifest.\n"
				  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				)
			      )


			      tweet( "Reader departing spaceLock PMKVDBNode Version 7 " + this + " for mget on " + path + "." )
			      //spaceLock.depart( Some( rk ) )
			      spaceLock.depart( path, Some( rk ) )
			      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
			      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

			      rk( oV )
			    }
			    case Some( pd ) => {
			      val xmlCollName =
				collName.getOrElse(
				  storeUnitStr.getOrElse(
				    bail()
				  )
				)
			      
			      // Defensively check that db is actually available
			      
			      checkIfDBExistsAndCreateIfNot( xmlCollName, true ) match {
				case false => {
				  tweet( 
				    (
				      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				      + "mgetting " + path + ".\n"
				      + "result in cache on " + this + " without a database.\n"
				      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
				    )
				  )

				  tweet( "Reader departing spaceLock PMKVDBNode Version 8 " + this + " for mget on " + path + "." )
				  //spaceLock.depart( Some( rk ) )
				  spaceLock.depart( path, Some( rk ) )
				  //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
				  //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

				  rk( oV )
				}
				case true => {
				  query( xmlCollName, path ) match {
				    case None => {
				      tweet( 
					(
					  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
					  + "mgetting " + path + ".\n"
					  + "result in cache on " + this + " without a query.\n"
					  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
					)
				      )

				      tweet( "Reader departing spaceLock PMKVDBNode Version 9 " + this + " for mget on " + path + "." )
				      //spaceLock.depart( Some( rk ) )
				      spaceLock.depart( path, Some( rk ) )
				      //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
				      //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

				      rk( oV )
				    }
				    case Some( qry ) => {
				      executeWithResults( xmlCollName, qry ) match {
					case Nil => {
					  tweet( 
					    (
					      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
					      + "mgetting " + path + ".\n"
					      + "result in cache on " + this + " no matching results in store.\n"
					      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
					    )
					  )
					  
					  //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
					  //tweet( "spaceLock writing room: " + spaceLock.writingRoom )

					  // Need to store continuation if
					  // this is a subscribe
					  consume match {
					    case policy : Subscription => {
					      storeKQuery( xmlCollName, pd )( path, rk )
					      rk( oV )
					    }
					    case _ => {
					      tweet( "Reader departing spaceLock PMKVDBNode Version 10" + this + " for mget on " + path + "." )
					      //spaceLock.depart( Some( rk ) )
					      spaceLock.depart( path, Some( rk ) )
					      rk( oV )
					    }
					  }
					  
					}
					case rslts => {
					  tweet(
					    (
					      "database "
					      + xmlCollName
					      + " had "
					      + rslts.length
					      + " matching resources."
					    )
					  )

					  // BUGBUG -- lgm : what to
					  // do in the case that
					  // what's in store doesn't
					  // match what's in cache?
					  for( rslt <- itergen[DBObject]( rslts ) ) {
					    tweet( "retrieved " + rslt.toString )					    
						
					    consume match {
					      case policy : RetainInStore => {
						tweet( "removing from store " + rslt )
						removeFromStore( 
						  persist,
						  rslt,
						  collName
						)
					      }
					      case _ => {
						tweet( "policy indicates not to remove from store" + rslt )
					      }
					    }
											    					  
					  }

					  //tweet( "Reader departing spaceLock PMKVDBNode Version 11" + this + " for mget on " + path + "." )
					  //spaceLock.depart( Some( rk ) )
					  //spaceLock.depart( path, Some( rk ) )
					  //tweet( "spaceLock reading room: " + spaceLock.readingRoom )
					  //tweet( "spaceLock writing room: " + spaceLock.writingRoom )
					  // Need to store continuation if
					  // this is a subscribe
					  consume match {
					    case policy : Subscription => {
					      storeKQuery( xmlCollName, pd )( path, rk )
					      rk( oV )
					    }
					    case _ => {
					      tweet( "Reader departing spaceLock PMKVDBNode Version 10" + this + " for mget on " + path + "." )
					      //spaceLock.depart( Some( rk ) )
					      spaceLock.depart( path, Some( rk ) )
					      rk( oV )
					    }
					  }					  
					}
				      }				      
				    }
				  }				  				  
				}				
			      }
			    }
			  }			  
			}
		      }		      
		    }
		  }
	      }
	  }
	}	
      }

      object BasePersistedMonadicKVDB {
	def apply [ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse,KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]] ( 
	  name : Moniker
	) : BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode] = {
	  new BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode]( name )
	}
	def unapply [ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse,KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]] (
	  pmkvdb : BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode]
	) : Option[( Moniker )] = {
	  Some( ( pmkvdb.name ) )
	}
      }

      class BasePersistedMonadicKVDBNode[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse,+KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]](
	override val cache : BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode],
	override val acquaintances : List[Moniker]
      ) extends BaseMonadicKVDBNode[ReqBody,RspBody,KVDBNode](
	cache, acquaintances
      ) with Serializable
      { 
	@transient
	implicit val resubmissionAsk : dAT.AskNum = dAT.AGetNum

	def resubmitRequests(
	  persist : Option[PersistenceManifest],
	  placeInstances : List[emT.PlaceInstance],
	  collName : Option[String]
	)(
	  implicit resubmissionAsk : dAT.AskNum
	) : Option[Generator[emT.PlaceInstance,Unit,Unit]] = {
	  val xmlCollName =
	    collName.getOrElse( cache.storeUnitStr.getOrElse( bail( ) ) )

	  for( pd <- persist ) yield {
	    Generator {
	      gk : ( emT.PlaceInstance => Unit @suspendable ) => 
		shift {
		  outerK : ( Unit => Unit ) =>
		    reset { 		      
		      for( pI <- itergen[emT.PlaceInstance]( placeInstances ) ){
			tweet( ">>>>>>***>>>>>>***>>>>>>" )
			tweet( "resubmitting " + pI.toString + " " )
			tweet( "<<<<<<***<<<<<<***<<<<<<" )
			//forward( resubmissionAsk, List[Moniker]( ), pI.place )
			gk( pI )
		      }		    
		    }
		}
	    }
	  }	    
	}

	def resubmitRequests(
	  persist : Option[PersistenceManifest],
	  path : CnxnCtxtLabel[Namespace,Var,Tag],
	  collName : Option[String]
	)(
	  implicit resubmissionAsk : dAT.AskNum
	) : Option[Generator[emT.PlaceInstance,Unit,Unit]] = {
	  resubmitRequests(
	    persist,
	    cache.pullKRecords(
	      persist,
	      path,
	      collName
	    ).getOrElse( List[emT.PlaceInstance]( ) ),
	    collName
	  )( resubmissionAsk )
	}

	override def dispatchDMsg( dreq : FramedMsg ) : Unit = {
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : dispatchDMsg "
	      + "\nthis : " + this
	      + "\ndreq : " + dreq
	    )
	  )
	  dreq match {
	    case Left( JustifiedRequest( msgId, mtrgt, msrc, lbl, body, _ ) ) => {
	      body match {
		case dgreq@Msgs.MDGetRequest( path ) => {	  
		  tweet( ( this + " getting locally for location : " + path ) )
		  reset {
		    for( v <- get( List( msrc ) )( false )( path ) ) {
		      tweet(
			(
			  this 
			  + " returning from local get for location : "
			  + path
			  + "\nwith value : " + v
			)
		      )
		      handleValue( dgreq, v, msrc )
		    }
		  }
		}
		
		case dfreq@Msgs.MDFetchRequest( path ) => {
		  tweet( ( this + "fetching locally for location : " + path ) )
		  reset {
		    for( v <- fetch( List( msrc ) )( false )( path ) ) {
		      tweet(
			(
			  this 
			  + " returning from local fetch for location : "
			  + path
			  + "\nwith value : " + v
			)
		      )
		      handleValue( dfreq, v, msrc )
		    }
		  }
		}
		
		case dsreq@Msgs.MDSubscribeRequest( path ) => {
		  tweet( ( this + "subscribing locally for location : " + path ) )
		  reset {
		    for( v <- subscribe( List( msrc ) )( path ) ) {
		      tweet(
			(
			  this 
			  + " returning from local subscribe for location : "
			  + path
			  + "\nwith value : " + v
			)
		      )
		      handleValue( dsreq, v, msrc )
		    }
		  }
		}
		
		case dpreq@Msgs.MDPutRequest( path, value ) => {	
		  reset { cache.put( path, mTT.Ground( value ) ) }
		  for( q <- stblQMap.get( msrc ) ) {
		    q ! wrapResponse( msrc, dpreq )
		  }
		}
		case dpbreq@Msgs.MDPublishRequest( path, value ) => {	
		  reset { cache.publish( path, mTT.Ground( value ) ) }
		  for( q <- stblQMap.get( msrc ) ) {
		    q ! wrapResponse( msrc, dpbreq )
		  }
		}
	      }
	    }
	    // BUGBUG -- lgm : DRY this please
	    case Right( JustifiedResponse( msgId, mtrgt, msrc, lbl, body, _ ) ) => {
	      body match {
		case RsrcMsgs.MDGetResponseRsrc( path, rsrc ) => {
		  rsrc match {
		    // if the rsrc comes with a substitution
		    // apply that to the path
		    case rbnd : mTT.RBound => {
		      rsrc( path ) match {
			// if the application results in a specialization
			// put the results there
			case Some( spec ) => {
			  for( inrRsrc <- rbnd.rsrc ) {
			    reset { put( spec, inrRsrc ) }
			  }		      		  
			}
			// else put the results at the path
			case None => {
			  reset { put( path, rsrc ) }
			}
		      }		  
		    }
		    case _ => {
		      reset { put( path, rsrc ) }
		    }
		  }	      
		}
		case RsrcMsgs.MDFetchResponseRsrc( path, rsrc ) => {
		  rsrc match {
		    case rbnd : mTT.RBound => {
		      rsrc( path ) match {
			case Some( spec ) => {
			  for( inrRsrc <- rbnd.rsrc ) {
			    reset { put( spec, inrRsrc ) }
			  }		      		  
			}
			case None => {
			  reset { put( path, rsrc ) }
			}
		      }		  
		    }
		    case _ => {
		      reset { put( path, rsrc ) }
		    }
		  }
		}
		case RsrcMsgs.MDSubscribeResponseRsrc( path, rsrc ) => {
		  rsrc match {
		    case rbnd : mTT.RBound => {
		      rsrc( path ) match {
			case Some( spec ) => {
			  for( inrRsrc <- rbnd.rsrc ) {
			    reset { publish( spec, inrRsrc ) }
			  }		      		  
			}
			case None => {
			  reset { publish( path, rsrc ) }
			}
		      }		  
		    }
		    case _ => {
		      reset { publish( path, rsrc ) }
		    }
		  }
		}	    
		case dput : RsrcMsgs.MDPutResponse[Namespace,Var,Tag,Value] => {	
		}
		case _ => {
		  tweet(
		    (
		      this 
		      + " handling unexpected message : "
		      + body
		    )
		  )
		}
	      }
	    }
	  }      
	  
	}		
	
	def mget( persist : Option[PersistenceManifest], ask : dAT.AskNum, hops : List[Moniker] )(
	  channels : Map[mTT.GetRequest,mTT.Resource],
	  registered : Map[mTT.GetRequest,List[RK]],
	  consume : RetentionPolicy,
	  keep : RetentionPolicy,
	  cursor : Boolean,
	  collName : Option[String]
	)(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	)
	: Generator[Option[mTT.Resource],Unit,Unit] = {        
	  tweet(
	    (
	      "PersistedMonadicKVDBNode : "
	      + "\nmethod : mget "
	      + "\nthis : " + this
	      + "\nchannels : " + channels
	      + "\nregistered : " + registered
	      + "\nconsume : " + consume
	      + "\nkeep : " + keep
	      + "\ncursor : " + cursor
	      + "\ncollName : " + collName
	      + "\npath : " + path
	      + "\n---------------------------------------"
	      + "\nchecking if this is a cache or a node: "
	      + "node" + "\n"
	    )
	  )
	  Generator {
	    rk : ( Option[mTT.Resource] => Unit @suspendable ) =>
	      shift {
		outerk : ( Unit => Unit ) =>
		  reset {
		    tweet( 
		      (
			"\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			+ "mgetting " + path + ".\n"
			+ "on " + this + ".\n"
			+ "checking local cache " + cache + ".\n"
			+ ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		      )
		    )
		    for(
		      oV <- cache.mget( persist, ask, hops )( channels, registered, consume, keep, cursor, collName )( path ) 
		    ) {
		      oV match {
			case None => {
			  //tweet( ">>>>> forwarding..." )
			  tweet( 
			    (
			      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			      + "mgetting " + path + ".\n"
			      + "on " + cache + " did not find a resource.\n"
			      + "xml collection name: " + collName + "\n"
			      + "consume data : " + true + "\n"
			      + "keep continuation : " + true + "\n"
			      + "forwarding to acquaintances.\n"
			      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
			    )
			  )

			  forward( ask, hops, path )
			  rk( oV )
			}
			case _ => rk( oV )
		      }
		    }
		  }
	      }
	  }      
	}
	
	override def get( hops : List[Moniker] )( cursor : Boolean )(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Generator[Option[mTT.Resource],Unit,Unit] = {	  
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  tweet( 
	    (
	      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      + "mgetting " + path + ".\n"
	      + "on " + this + "\n"
	      + "persistence manifest: " + perD + "\n"
	      + "xml collection name: " + xmlCollName + "\n"
	      + "consume data : " + true + "\n"
	      + "keep continuation : " + true + "\n"
	      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	    )
	  )
	  mget( perD, dAT.AGetNum, hops )( cache.theMeetingPlace, cache.theWaiters, CacheAndStore, Store, cursor, xmlCollName )( path )    
	}
		    			
	override def fetch( hops : List[Moniker] )(
	  cursor : Boolean
	)(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	)
	: Generator[Option[mTT.Resource],Unit,Unit] = {              
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  mget( perD, dAT.AFetchNum, hops )(
	    cache.theMeetingPlace, cache.theWaiters, DoNotRetain, DoNotRetain, cursor, xmlCollName
	  )( path )    
	}				
	
	override def subscribe( hops : List[Moniker] )(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	)
	: Generator[Option[mTT.Resource],Unit,Unit] = {        
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  mget( perD, dAT.ASubscribeNum, hops )(
	    cache.theChannels, cache.theSubscriptions, CacheAndStoreSubscription, Store, false, xmlCollName
	  )( path )    
	}
			
	override def put( ptn : CnxnCtxtLabel[Namespace,Var,Tag], rsrc : mTT.Resource ) = {
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  cache.mput( perD )( cache.theMeetingPlace, cache.theWaiters, false, xmlCollName )( ptn, rsrc )
	}
	override def publish( ptn : CnxnCtxtLabel[Namespace,Var,Tag], rsrc : mTT.Resource ) = {
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  cache.mput( perD )( cache.theChannels, cache.theSubscriptions, true, xmlCollName )( ptn, rsrc )
	}

	def unbindAndResubmit( hops : List[Moniker] )(
	  path : CnxnCtxtLabel[Namespace,Var,Tag]
	) : Unit = {	  
	  val perD = cache.persistenceManifest
	  val xmlCollName = 
	    perD match {
	      case None => None
	      case Some( pd ) => Some( pd.storeUnitStr )
	    }
	  tweet( 
	    (
	      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      + "unbinding cached continuations " + path + ".\n"
	      + "on " + this + "\n"
	      + "persistence manifest: " + perD + "\n"
	      + "xml collection name: " + xmlCollName + "\n"
	      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	    )
	  )
	  for( placeInstances <- cache.pullKRecords( perD, path, xmlCollName ) ) {
	    resubmitRequests( perD, placeInstances, xmlCollName )
	  }
	}
		 
      }

      object BasePersistedMonadicKVDBNode {
	def apply [ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse,KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]] ( 
	  cache : BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode],
	  acquaintances : List[Moniker]
	) : BasePersistedMonadicKVDBNode[ReqBody,RspBody,KVDBNode] = {
	  new BasePersistedMonadicKVDBNode[ReqBody,RspBody,KVDBNode]( cache, acquaintances )
	}
	def unapply [ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse,KVDBNode[Rq <: ReqBody, Rs <: RspBody] <: BasePersistedMonadicKVDBNode[Rq,Rs,KVDBNode]] (
	  pmkvdbnode : BasePersistedMonadicKVDBNode[ReqBody,RspBody,KVDBNode]
	) : Option[( BasePersistedMonadicKVDB[ReqBody,RspBody,KVDBNode], List[Moniker] )] = {
	  Some( ( pmkvdbnode.cache, pmkvdbnode.acquaintances ) )
	}
      }

      case class PersistedMonadicKVDB[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
	override val name : Moniker
      ) extends BasePersistedMonadicKVDB[ReqBody,RspBody,PersistedMonadicKVDBNode](
	name
      ) 

      case class PersistedMonadicKVDBNode[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
	override val cache : PersistedMonadicKVDB[ReqBody,RspBody],
	override val acquaintances : List[Moniker]
      ) extends BasePersistedMonadicKVDBNode[ReqBody,RspBody,PersistedMonadicKVDBNode](
	cache, acquaintances
      ) 
      
      trait PersistedKVDBNodeFactoryT
        extends AMQPURIOps
        with ThreadPoolRunnersX
        //with FJTaskRunnersX
      {
	def ptToPt[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI, there : URI ) : PersistedMonadicKVDBNode[ReqBody,RspBody]
	def loopBack[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI ) : PersistedMonadicKVDBNode[ReqBody,RspBody]
      }
    }
}


package usage {
  object PersistedMonadicKVDBMongoNet
       extends PersistedMonadicKVDBMongoNodeScope[String,String,String,Double]
       with UUIDOps
  with Serializable
  {
    import SpecialKURIDefaults._
    import identityConversions._

    type MTTypes = MonadicTermTypes[String,String,String,Double]
    object TheMTT extends MTTypes with Serializable
    override def protoTermTypes : MTTypes = TheMTT

    type DATypes = DistributedAskTypes
    object TheDAT extends DATypes with Serializable
    override def protoAskTypes : DATypes = TheDAT
    
    override type MsgTypes = DTSMSHRsrc   
    override type RsrcMsgTypes = DTSMSHRsrc   
    
    @transient
    val protoDreqUUID = getUUID()
    @transient
    val protoDrspUUID = getUUID()    

    @transient
    lazy val aLabel = new CnxnCtxtLeaf[String,String,String]( Left( "a" ) )

    object MonadicDRsrcMsgs extends RsrcMsgTypes with Serializable {
      
      @transient
      override def protoDreq : DReq = MDGetRequest( aLabel )
      @transient
      override def protoDrsp : DRsp = MDGetResponse( aLabel, 0.0 )
      @transient
      override def protoJtsreq : JTSReq =
	JustifiedRequest(
	  protoDreqUUID,
	  new URI( "agent", protoDreqUUID.toString, "/invitation", "" ),
	  new URI( "agent", protoDreqUUID.toString, "/invitation", "" ),
	  getUUID(),
	  protoDreq,
	  None
	)
      @transient
      override def protoJtsrsp : JTSRsp = 
	JustifiedResponse(
	  protoDreqUUID,
	  new URI( "agent", protoDrspUUID.toString, "/invitation", "" ),
	  new URI( "agent", protoDrspUUID.toString, "/invitation", "" ),
	  getUUID(),
	  protoDrsp,
	  None
	)
      override def protoJtsreqorrsp : JTSReqOrRsp =
	Left( protoJtsreq )
    }
    
    override def protoMsgs : MsgTypes = MonadicDRsrcMsgs
    override def protoRsrcMsgs : RsrcMsgTypes = MonadicDRsrcMsgs

    object Being extends PersistenceScope with Serializable {      
      override type EMTypes = ExcludedMiddleTypes[mTT.GetRequest,mTT.GetRequest,mTT.Resource]
      object theEMTypes extends ExcludedMiddleTypes[mTT.GetRequest,mTT.GetRequest,mTT.Resource]
       with Serializable
      {
	case class PrologSubstitution( soln : LinkedHashMap[String,CnxnCtxtLabel[String,String,String]] )
	   extends Function1[mTT.Resource,Option[mTT.Resource]] {
	     override def apply( rsrc : mTT.Resource ) = {
	       Some( mTT.RBoundHM( Some( rsrc ), Some( soln ) ) )
	     }
	   }
	override type Substitution = PrologSubstitution	
      }      

      override def protoEMTypes : EMTypes =
	theEMTypes

      object PersistedKVDBNodeFactory extends PersistedKVDBNodeFactoryT with Serializable {	  
	def mkCache[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI ) : PersistedMonadicKVDB[ReqBody,RspBody] = {
	  new PersistedMonadicKVDB[ReqBody, RspBody]( MURI( here ) ) with Blobify with AMQPMonikerOps {		
	    class StringMongoDBManifest(
	      override val storeUnitStr : String,
	      @transient override val labelToNS : Option[String => String],
	      @transient override val textToVar : Option[String => String],
	      @transient override val textToTag : Option[String => String]
	    )
	    extends MongoDBManifest( ) {
	      override def valueStorageType : String = {
		throw new Exception( "valueStorageType not overriden in instantiation" )
	      }
	      override def continuationStorageType : String = {
		throw new Exception( "continuationStorageType not overriden in instantiation" )
	      }
	      
	      override def storeUnitStr[Src,Label,Trgt]( cnxn : Cnxn[Src,Label,Trgt] ) : String = {     
		cnxn match {
		  case CCnxn( s, l, t ) => s.toString + l.toString + t.toString
		}	    
	      }	
	      
	      def kvNameSpace : String = "record"
	      def kvKNameSpace : String = "kRecord"
	      
	      def compareNameSpace( ns1 : String, ns2 : String ) : Boolean = {
		ns1.equals( ns2 )
	      }
	      
	      override def asStoreValue(
		rsrc : mTT.Resource
	      ) : CnxnCtxtLeaf[String,String,String] with Factual = {
		tweet(
		  "In asStoreValue on " + this + " for resource: " + rsrc
		)
		val storageDispatch = 
		  rsrc match {
		    case k : mTT.Continuation => {
		      tweet(
			"Resource " + rsrc + " is a continuation"
		      )
		      continuationStorageType
		    }
		    case _ => {
		      tweet(
			"Resource " + rsrc + " is a value"
		      )
		      valueStorageType
		    }
		  };
		
		tweet(
		  "storageDispatch: " + storageDispatch
		)
		
		val blob =
		  storageDispatch match {
		    case "Base64" => {
		      val baos : ByteArrayOutputStream = new ByteArrayOutputStream()
		      val oos : ObjectOutputStream = new ObjectOutputStream( baos )
		      oos.writeObject( rsrc.asInstanceOf[Serializable] )
		      oos.close()
		      new String( Base64Coder.encode( baos.toByteArray() ) )
		    }
		    case "CnxnCtxtLabel" => {
		      tweet(
			"warning: CnxnCtxtLabel method is using XStream"
		      )
		      toXQSafeJSONBlob( rsrc )		  		  
		    }
		    case "XStream" => {
		      tweet(
			"using XStream method"
		      )
		      
		      toXQSafeJSONBlob( rsrc )
		    }
		    case _ => {
		      throw new Exception( "unexpected value storage type" )
		    }
		  }
		new CnxnCtxtLeaf[String,String,String](
		  Left[String,String]( blob )
		)
	      }
	      
	      def asCacheValue(
		ccl : CnxnCtxtLabel[String,String,String]
	      ) : Double = {
		tweet(
		  "converting to cache value"
		)
		ccl match {
		  case CnxnCtxtBranch(
		    "string",
		    CnxnCtxtLeaf( Left( rv ) ) :: Nil
		  ) => {
		    val unBlob =
		      fromXQSafeJSONBlob( rv )
		    
		    unBlob match {
		      case rsrc : mTT.Resource => {
			getGV( rsrc ).getOrElse( java.lang.Double.MAX_VALUE )
		      }
		    }
		  }
		  case _ => {
		    //asPatternString( ccl )
		    throw new Exception( "unexpected value form: " + ccl )
		  }
		}
	      }
	      
	      override def asResource(
		key : mTT.GetRequest, // must have the pattern to determine bindings
		value : DBObject
	      ) : emT.PlaceInstance = {
		// BUGBUG -- lgm : what was the rationale for the
		// roundtrip?
		/*
		val cclKey =
		  xmlIfier.fromXML( ltns, ttv, ttt )(
		    xmlIfier.asXML( key )
		  ) match {
		    case Some( cclX ) => cclX
		    case _ => throw new Exception( "xml roundtrip failed " + key )
 		  }
		*/
		val ltns =
		  labelToNS.getOrElse(
		    throw new Exception( "must have labelToNS to convert mongo object" )
		  )
		val ttv =
		  textToVar.getOrElse(
		    throw new Exception( "must have textToVar to convert mongo object" )
		  )
		val ttt =
		  textToTag.getOrElse(
		    throw new Exception( "must have textToTag to convert mongo object" )
		  )
		CnxnMongoObjectifier.fromMongoObject( value )( ltns, ttv, ttt ) match {
		  case CnxnCtxtBranch( ns, CnxnCtxtBranch( kNs, k :: Nil ) :: CnxnCtxtBranch( vNs, v :: Nil ) :: Nil ) => {
		    tweet( " ****************************** " )
		    tweet( "vNs: " + vNs )
		    tweet( "v: " + v )
		    tweet( " ****************************** " )
		    matchMap( key, k ) match {
		      case Some( soln ) => {
			if ( compareNameSpace( ns, kvNameSpace ) ) {
			  emT.PlaceInstance(
			    k,
			    Left[mTT.Resource,List[Option[mTT.Resource] => Unit @suspendable]](
			      mTT.Ground(
				asCacheValue(
				  new CnxnCtxtBranch[String,String,String](
				    "string",
				    v :: Nil
				  )
				)
			      )
			    ),
			    // BUGBUG -- lgm : why can't the compiler determine
			    // that this cast is not necessary?
			    theEMTypes.PrologSubstitution( soln ).asInstanceOf[emT.Substitution]
			  )
			}
			else {
			  if ( compareNameSpace( ns, kvKNameSpace ) ) {
			    val mTT.Continuation( ks ) =
			      asCacheK(
				new CnxnCtxtBranch[String,String,String](
				  "string",
				  v :: Nil
				)
			      )
			    emT.PlaceInstance(
			      k,
			      Right[mTT.Resource,List[Option[mTT.Resource] => Unit @suspendable]]( 
				ks
			      ),
			      // BUGBUG -- lgm : why can't the compiler determine
			      // that this cast is not necessary?
			      theEMTypes.PrologSubstitution( soln ).asInstanceOf[emT.Substitution]
			    )
			  }
			  else {
			    throw new Exception( "unexpected namespace : (" + ns + ")" )
			  }
			}
		      }
		      case None => {
			tweet( "Unexpected matchMap failure: " + key + " " + k )
			throw new Exception( "matchMap failure " + key + " " + k )
		      }
		    }
		  }
		  case _ => {
		    throw new Exception( "unexpected record format : " + value )
		  }
		}		
	      }	      
	    }
	    override def asCacheK(
	      ccl : CnxnCtxtLabel[String,String,String]
	    ) : Option[mTT.Continuation] = {
	      tweet(
		"converting to cache continuation stack" + ccl
	      )
	      ccl match {
		case CnxnCtxtBranch(
		  "string",
		  CnxnCtxtLeaf( Left( rv ) ) :: Nil
		) => {
		  val unBlob =
		    continuationStorageType match {
		      case "CnxnCtxtLabel" => {
			// tweet(
			// 		      "warning: CnxnCtxtLabel method is using XStream"
			// 		    )
			fromXQSafeJSONBlob( rv )
		      }
		      case "XStream" => {
			fromXQSafeJSONBlob( rv )
		      }
		      case "Base64" => {
			val data : Array[Byte] = Base64Coder.decode( rv )
			val ois : ObjectInputStream =
			  new ObjectInputStream( new ByteArrayInputStream(  data ) )
			val o : java.lang.Object = ois.readObject();
			ois.close()
			o
		      }
		    }
		  
		  unBlob match {
		    case k : mTT.Resource => {
		      Some( k.asInstanceOf[mTT.Continuation] )
		    }
		    case _ => {
		      throw new Exception(
			(
			  ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
			  + "ill-formatted continuation stack blob : " + rv
			  + "\n" 
			  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
			  + "\n"
			  + "unBlob : " + unBlob
			  + "\n"
			  + "unBlob type : " + unBlob
			  + "\n"
			  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
			)
		      )
		    }
		  }
		}
		case _ => {
		  throw new Exception( "ill-formatted continuation stack leaf: " + ccl )
		}
	      }
	    }
	    
	    override def asCacheK(
	      ltns : String => String,
	      ttv : String => String,
	      value : DBObject
	    ) : Option[mTT.Continuation] = {
	      throw new Exception( "shouldn't be calling this version of asCacheK" )
	    }
	    override def persistenceManifest : Option[PersistenceManifest] = {
	      val sid = Some( ( s : String ) => recoverFieldName( s ) )
	      val kvdb = this;
	      Some(
		new StringMongoDBManifest( dfStoreUnitStr, sid, sid, sid ) {
		  override def valueStorageType : String = {
		    kvdb.valueStorageType
		  }
		  override def continuationStorageType : String = {
		    kvdb.continuationStorageType
		  }
		}
	      )
	    }
	    def dfStoreUnitStr : String = mnkrExchange( name )
	  }
	}
	def ptToPt[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI, there : URI ) : PersistedMonadicKVDBNode[ReqBody,RspBody] = {
	  val node =
	    PersistedMonadicKVDBNode[ReqBody,RspBody](
	      mkCache( MURI( here ) ),
	      List( MURI( there ) )
	    )
	  spawn { node.dispatchDMsgs() }
	  node
	}
	def ptToMany[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI, there : List[URI] ) : PersistedMonadicKVDBNode[ReqBody,RspBody] = {
	  val node =
	    PersistedMonadicKVDBNode[ReqBody,RspBody](
	      mkCache( MURI( here ) ),
	      there.map( MURI( _ ) )
	    )
	  spawn { node.dispatchDMsgs() }
	  node
	}
	def loopBack[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( here : URI ) : PersistedMonadicKVDBNode[ReqBody,RspBody] = {
	  val exchange = uriExchange( here )
	  val hereNow =
	    new URI(
	      here.getScheme,
	      here.getUserInfo,
	      here.getHost,
	      here.getPort,
	      "/" + exchange + "Local",
	      here.getQuery,
	      here.getFragment
	    )
	  val thereNow =
	    new URI(
	      here.getScheme,
	      here.getUserInfo,
	      here.getHost,
	      here.getPort,
	      "/" + exchange + "Remote",
	      here.getQuery,
	      here.getFragment
	    )	    
	  
	  val node =
	    PersistedMonadicKVDBNode[ReqBody, RspBody](
	      mkCache( MURI( hereNow ) ),
	      List( MURI( thereNow ) )
	    )
	  spawn { node.dispatchDMsgs() }
	  node
	}
      }
    }
  }
  
  object PersistedMongoMolecularUseCase extends Serializable {
    import PersistedMonadicKVDBMongoNet._   
    import Being._
    import PersistedKVDBNodeFactory._

    implicit val retTwist : Boolean = false
    def setup[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
      localHost : String, localPort : Int,
      remoteHost : String, remotePort : Int
    )(
      implicit returnTwist : Boolean
    ) : Either[Being.PersistedMonadicKVDBNode[ReqBody,RspBody],(Being.PersistedMonadicKVDBNode[ReqBody, RspBody],Being.PersistedMonadicKVDBNode[ReqBody, RspBody])] = {
      val ( localExchange, remoteExchange ) = 
	if ( localHost.equals( remoteHost ) && ( localPort == remotePort ) ) {
	  ( "/molecularUseCaseProtocolLocal", "/molecularUseCaseProtocolRemote" )	  
	}
	else {
	  ( "/molecularUseCaseProtocol", "/molecularUseCaseProtocol" )	  
	}

      if ( returnTwist ) {
	Right[Being.PersistedMonadicKVDBNode[ReqBody,RspBody],(Being.PersistedMonadicKVDBNode[ReqBody, RspBody],Being.PersistedMonadicKVDBNode[ReqBody, RspBody])](
	  (
	    ptToPt[ReqBody, RspBody](
	      new URI( "agent", null, localHost, localPort, localExchange, null, null ),
	      new URI( "agent", null, remoteHost, remotePort, remoteExchange, null, null )
	    ),
	    ptToPt[ReqBody, RspBody](	      
	      new URI( "agent", null, remoteHost, remotePort, remoteExchange, null, null ),
	      new URI( "agent", null, localHost, localPort, localExchange, null, null )
	    )
	  )
	)
      }
      else {
	Left[Being.PersistedMonadicKVDBNode[ReqBody, RspBody],(Being.PersistedMonadicKVDBNode[ReqBody, RspBody],Being.PersistedMonadicKVDBNode[ReqBody, RspBody])](
	  ptToPt(
	    new URI( "agent", null, localHost, localPort, localExchange, null, null ),
	    new URI( "agent", null, remoteHost, remotePort, remoteExchange, null, null )
	  )
	)
      }
    }

    object KinaseSpecifications extends Serializable {
      import scala.math._
      
      trait Kinase {
	def b : Boolean
	def i : Int
	def state : String
	def update( j : Int ) : ConcreteKinase 
      }
      case class RAF(
	b : Boolean, i : Int, state : String
      ) extends Kinase {
	override def update( j : Int ) : ConcreteKinase = RAF( b, j, state )
      }
      case class RAS(
	b : Boolean, i : Int, state : String
      ) extends Kinase {
	override def update( j : Int ) : ConcreteKinase = RAS( b, j, state )
      }
      case class MEK1(
	b : Boolean, i : Int, state : String
      ) extends Kinase {
	override def update( j : Int ) : ConcreteKinase = MEK1( b, j, state )
      }
      case class MEK2(
	b : Boolean, i : Int, state : String
      ) extends Kinase {
	override def update( j : Int ) : ConcreteKinase = MEK2( b, j, state )
      }
      case class MAPK(
	b : Boolean, i : Int, state : String
      ) extends Kinase {
	override def update( j : Int ) : ConcreteKinase = MAPK( b, j, state )
      }
      
      @transient
      lazy val RAFProto : RAF = RAF( true, 0, "Phosphorylated" )
      @transient
      lazy val RASProto : RAS = RAS( true, 0, "Phosphorylated" )
      @transient
      lazy val MEK1Proto : MEK1 = MEK1( true, 0, "Phosphorylated" )
      @transient
      lazy val MEK2Proto : MEK2 = MEK2( true, 0, "Phosphorylated" )      
      @transient
      lazy val MAPKProto : MAPK = MAPK( true, 0, "Phosphorylated" )      

      def mkMolQry( kinase : Kinase ) : CnxnCtxtLabel[String,String,String] = {
	import CnxnConversionStringScope._
	kinase match {
	  case cc : ScalaObject with Product with Serializable => {
	    asCnxnCtxtLabel( cc )
	  }
	  case _ => throw new Exception( "non concrete kinase: " + kinase )
	}	
      }

      def mkMolPtn( molType : String ) : CnxnCtxtLabel[String,String,String] = {
	new CnxnCtxtBranch[String,String,String](
	  "comBiosimilarityLiftModelStoreUsagePersistedMongoMolecularUseCase_KinaseSpecifications_" + molType,
	  List( 
	    new CnxnCtxtLeaf[String,String,String](
	      Right[String,String]( "B" )
	    ),
	    new CnxnCtxtLeaf[String,String,String](
	      Right[String,String]( "I" )
	    ),
	    new CnxnCtxtBranch[String,String,String](
	      "state",
	      List(
		new CnxnCtxtLeaf[String,String,String](
		  Left[String,String]( "Phosphorylated" )
		)
	      )
	    )
	  )
	)
      }

      type ConcreteKinase = Kinase with Product with Serializable

      @transient
      lazy val molPtnMap : HashMap[ConcreteKinase,CnxnCtxtLabel[String,String,String]] = {	
	val map = new HashMap[ConcreteKinase,CnxnCtxtLabel[String,String,String]]()
	map += ( RAFProto -> mkMolPtn( "RAF" ) )
	map += ( RASProto -> mkMolPtn( "RAS" ) )
	map += ( MEK1Proto -> mkMolPtn( "MEK1" ) )
	map += ( MEK2Proto -> mkMolPtn( "MEK2" ) )
	map += ( MAPKProto -> mkMolPtn( "MAPK" ) )
	map
      }

      @transient
      implicit lazy val cascade : Seq[ConcreteKinase] =
	List[ConcreteKinase](
	  RAFProto, RASProto, MEK1Proto, MEK2Proto, MAPKProto
	)

      @transient
      implicit lazy val cascadeInitialState : List[( ConcreteKinase, Option[ConcreteKinase] )] = {
	cascade.zip( cascade.drop( 1 ).map( Some( _ ) ) ++ List( None ) ).toList
      }

      @transient
      implicit lazy val initialKinaseToProduce : ConcreteKinase = {	
	cascade.head
      }	

      //def raf2RAS : Double = random * 100
      def raf2RAS : Double = .10 * 100
      //def ras2MEK1 : Double = random * 100
      def ras2MEK1 : Double = .20 * 100
      //def mek12MEK2 : Double = random * 100
      def mek12MEK2 : Double = .30 * 100
      //def mek22MAPK : Double = random * 100
      def mek22MAPK : Double = .40 * 100
      //def mapk2Protein : Double = random * 100            
      def mapk2Protein : Double = .50 * 100            

      @transient
      lazy val cascadeTransitionMap : HashMap[ConcreteKinase,Double] = {
	val map = new HashMap[ConcreteKinase,Double]()
	map += ( RAFProto -> raf2RAS )
	map += ( RASProto -> ras2MEK1 )
	map += ( MEK1Proto -> mek12MEK2 )
	map += ( MEK2Proto -> mek22MAPK )
	map += ( MAPKProto -> mapk2Protein )
	map
      }

      @transient
      lazy val RAFPtn : CnxnCtxtLabel[String,String,String] =
	molPtnMap( RAFProto )
      
      @transient
      lazy val RASPtn : CnxnCtxtLabel[String,String,String] =
	molPtnMap( RASProto )

      @transient
      lazy val MEK1Ptn : CnxnCtxtLabel[String,String,String] =
	molPtnMap( MEK1Proto )

      @transient
      lazy val MEK2Ptn : CnxnCtxtLabel[String,String,String] =
	molPtnMap( MEK2Proto )
            
      @transient
      lazy val MAPKPtn : CnxnCtxtLabel[String,String,String] =
	molPtnMap( MAPKProto )
    }

    import KinaseSpecifications._

    trait CellularEnvironment {
      def kinaseMap : HashMap[CnxnCtxtLabel[String,String,String],Double] 
      def amt [K <: CnxnCtxtLabel[String,String,String]] ( proto : K ) : Double = {
	kinaseMap.get( proto ).getOrElse( 0 )
      }      
    }
    
    case class Cytoplasm(
      pkm : ListBuffer[(CnxnCtxtLabel[String,String,String],Double)]
    ) extends CellularEnvironment with MapProxy[CnxnCtxtLabel[String,String,String],Double] {
      @transient var _kmap : Option[HashMap[CnxnCtxtLabel[String,String,String],Double]] = None
      override def self = kinaseMap
      override def kinaseMap = {
	_kmap match {
	  case Some( kmap ) => {
	    kmap
	  }
	  case null => {
	    val kmap = new HashMap[CnxnCtxtLabel[String,String,String],Double]()
	    for( ( ccl, amt ) <- pkm ) {
	      kmap += ( ccl -> amt )
	    }
	    _kmap = Some( kmap )
	    kmap
	  }
	  case None => {
	    val kmap = new HashMap[CnxnCtxtLabel[String,String,String],Double]()
	    for( ( ccl, amt ) <- pkm ) {
	      kmap += ( ccl -> amt )
	    }
	    _kmap = Some( kmap )
	    kmap
	  }
	}	
      }
    }

    @transient
    implicit lazy val cellCytoplasm : Cytoplasm =
      Cytoplasm(
	new ListBuffer[(CnxnCtxtLabel[String,String,String],Double)]()
      )    

    def supplyKinase[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
      kvdbNode : Being.PersistedMonadicKVDBNode[ReqBody,RspBody],
      cellCytoplasm : Cytoplasm,
      kinase : ConcreteKinase,
      trigger : Double
    ) : Unit = {
      import scala.math._
      import CnxnConversionStringScope._
      import cnxnConversions._
      new Thread {
	override def run() : Unit = {
	  def loop( proto : ConcreteKinase, kns : ConcreteKinase, amt : Double, count : Int ) : Unit = {
	    val kinasePtn = molPtnMap( proto )
	    val kamt = cellCytoplasm.amt( kinasePtn )
	    if ( kamt < amt ) {
	      val inc = random * 25
	      val nkns = kns.update( count + 1 )
	      cellCytoplasm += ( kinasePtn -> ( kamt + inc ) )
	      cellCytoplasm.pkm += ( ( kinasePtn, ( kamt + inc ) ) )
	      reset { 
		println(
		  (
		    "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		    + kvdbNode + "\n"
		    + "releasing an increment " + inc + " of " + kns + "\n"
		    + "total amount released: " + ( kamt + inc )
		    + "loop count: " + count + "\n"
		    + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		  )
		)
		kvdbNode.put( mkMolQry( nkns ), inc )
	      }
	      loop( proto, nkns, amt, ( count + 1 ) )
	    }
	  }

	  loop( kinase, kinase, trigger, 0 )

	}
      }.start
    }    

    def handleRsrc[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
      kvdbNode : Being.PersistedMonadicKVDBNode[ReqBody,RspBody],
      cellCytoplasm : Cytoplasm,
      kinasePair : ( ConcreteKinase, Option[ConcreteKinase] )
    )(
      trigger : Double,
      inc : Double
    ) : Unit = {
      val ( kinaseToConsumeProto, optKinaseToProduceProto ) = kinasePair
      val kinaseToConsumeProtoPtn = molPtnMap( kinaseToConsumeProto )
      println(
	(
	  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	  + kvdbNode + "\n"
	  + "received an increment, "
	  + inc
	  + ", of "
	  + kinaseToConsumeProto + "\n"
	  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	)
      )
      val currAmt : Double = cellCytoplasm.amt( kinaseToConsumeProtoPtn )
      val nAmt : Double = ( currAmt + inc );
      cellCytoplasm += ( ( kinaseToConsumeProtoPtn, nAmt ) )
      cellCytoplasm.pkm += ( ( kinaseToConsumeProtoPtn, nAmt ) )
      
      println(
	(
	  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	  + kvdbNode + "\n"
	  + "has accumulated " + nAmt + " of "
	  + kinaseToConsumeProto + "\n"
	  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	)
      )
      
      optKinaseToProduceProto match {
	case Some( kinaseToProduceProto ) => {
	  for( amt <- cellCytoplasm.get( kinaseToConsumeProtoPtn ) ) {
	    // Got enough!
	    if ( amt > trigger ) {
	      println( 
		(
		  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		  + kvdbNode + "\n"
		  + "received enough "
		  + kinaseToConsumeProto
		  + " to produce "
		  + kinaseToProduceProto + "\n"
		  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		)
	      )

	      for( nextTrigger <- cascadeTransitionMap.get( kinaseToProduceProto ) ) {
		println(
		  (
		    "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		    + kvdbNode + "\n"
		    + "the trigger for the next transition is: " + nextTrigger + "\n"
		    + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		  )
		)
		
		// Supply some RAS
		supplyKinase(
		  kvdbNode,
		  cellCytoplasm,
		  kinaseToProduceProto,
		  nextTrigger
		)				
	      }
	    }
	    // Not quite enough...
	    else {
	      println(
		(
		  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		  + kvdbNode + "\n"
		  + "still waiting for enough "
		  + kinaseToConsumeProto
		  + " to produce "
		  + kinaseToProduceProto + "\n"
		  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		)
	      )
	      processKinasePair(
		kvdbNode,
		cellCytoplasm,
		kinasePair
	      )
	    }
	  }		    		    
	}
	case _ => {
	  println( 
	    (
	      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      + kvdbNode + "\n"
	      + "producing Protein.\n"
	      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	    )
	  )
	}
      }		
    }
    
    def processKinasePair[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse](
      kvdbNode : Being.PersistedMonadicKVDBNode[ReqBody,RspBody],
      cellCytoplasm : Cytoplasm,
      kinasePair : ( ConcreteKinase, Option[ConcreteKinase] )
    ) : Unit = {            
      println( 
	(
	  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	  + kvdbNode + "\n"
	  + "processing kinase pair "
	  + kinasePair + ".\n"
	  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	)
      )

      val handleKinase = handleRsrc( kvdbNode, cellCytoplasm, kinasePair ) _

      val ( kinaseToConsumeProto, optKinaseToProduceProto ) = kinasePair
      val kinasePtn = molPtnMap( kinaseToConsumeProto )
      val trigger = cascadeTransitionMap.get( kinaseToConsumeProto ).getOrElse( java.lang.Double.MAX_VALUE )
      
      reset {
	// Wait for kinase
	for( kinaseRsrc <- kvdbNode.get( kinasePtn ) ) {
	  println(
	    (
	      "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	      + kvdbNode + " received resource : " + kinaseRsrc + "\n"
	      + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
	    )
	  )
	  kinaseRsrc match {
	    // Got some!
	    case Some( mTT.RBoundAList( Some( mTT.Ground( inc ) ), soln ) ) => {
	      handleKinase( trigger, inc )
	    }
	    case Some( mTT.RBoundHM( Some( mTT.Ground( inc ) ), soln ) ) => {
	      handleKinase( trigger, inc )
	    }
	    case Some( mTT.Ground( inc ) ) => {
	      handleKinase( trigger, inc )
	    }
	    // Got none... so wait
	    case None => {
	      println( 
		(
		  "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		  + kvdbNode + " received nothing; waiting for kinase, "
		  + kinaseToConsumeProto + ".\n"
		  + ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n"
		)
	      )
	    }
	    case unExpected@_ => {
	      throw new Exception( "Protocol violated. Received: " + unExpected )
	    }	    
	  }
	}
      }
    }

    def runClient[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( kvdbNode : Being.PersistedMonadicKVDBNode[ReqBody,RspBody] )( implicit cellCytoplasm : Cytoplasm ) : Unit = {
      import scala.math._
      import KinaseSpecifications._
      // map-reduce-style protocol checking      
      new Thread {
	override def run() : Unit = {
	  processKinasePair( kvdbNode, cellCytoplasm, cascadeInitialState( 0 ) )
	}
      }.start
      new Thread {
	override def run() : Unit = {
	  processKinasePair( kvdbNode, cellCytoplasm, cascadeInitialState( 2 ) )
	}
      }.start
      new Thread {
	override def run() : Unit = {
	  processKinasePair( kvdbNode, cellCytoplasm, cascadeInitialState( 4 ) )
	}
      }.start
    }

    def runServer[ReqBody <: PersistedKVDBNodeRequest, RspBody <: PersistedKVDBNodeResponse]( kvdbNode : Being.PersistedMonadicKVDBNode[ReqBody,RspBody] )( implicit cellCytoplasm : Cytoplasm ) : Unit = {
      import scala.math._
      import KinaseSpecifications._
      // map-reduce-style protocol             
      new Thread {
	override def run() : Unit = {
	  supplyKinase( kvdbNode, cellCytoplasm, RAFProto, raf2RAS )
	}
      }.start
      new Thread {
	override def run() : Unit = {	  
	  processKinasePair( kvdbNode, cellCytoplasm, cascadeInitialState( 1 ) )
	}
      }.start
      new Thread {
	override def run() : Unit = {
	  processKinasePair( kvdbNode, cellCytoplasm, cascadeInitialState( 3 ) )
	}
      }.start
    }
    
  }
}
