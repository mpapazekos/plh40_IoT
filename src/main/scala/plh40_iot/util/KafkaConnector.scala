package plh40_iot.util

import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.ConsumerSettings
import akka.kafka.ProducerSettings
import akka.kafka.Subscription
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Producer
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSink
import akka.stream.scaladsl.SourceWithContext
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

object KafkaConnector {
 
  def committableSourceWithOffsetContext[Result](
    consumerSettings: ConsumerSettings[String, String], 
    topics: Subscription, 
    parseMessage: String => Result
  )(implicit ec: ExecutionContext): SourceWithContext[Result, CommittableOffset, Consumer.Control] = {
    Consumer
          .committableSource(consumerSettings, topics)
          .mapAsync(4)(msg => parseWithOffset(msg.record.value(), msg.committableOffset, parseMessage))
          .via(Utils.errorHandleFlow())
          .asSourceWithContext(resOffest => resOffest._2)
          .map(resOffest => resOffest._1)
  }

  def producerSettings(clientId: String): ProducerSettings[String, String] = { 
      val config = ConfigFactory.load("kafka").getConfig("akka.kafka.producer")
        
      ProducerSettings(config, new StringSerializer, new StringSerializer)
          .withClientId(clientId)       
  }

  def consumerSettings(clientId: String, groupId: String): ConsumerSettings[String, String] = { 
      val config = ConfigFactory.load("kafka").getConfig("akka.kafka.consumer")
        
      ConsumerSettings(config, new StringDeserializer, new StringDeserializer)
          .withClientId(clientId)
          .withGroupId(groupId)
  }

  def plainRestartProducer(producerSettings: ProducerSettings[String, String]) = {
    val restartSettings =  
      RestartSettings(100.millis, 10.seconds, randomFactor = 0.2d).withMaxRestarts(20, 5.minutes)

    val producer = Producer.plainSink(producerSettings)

    RestartSink.withBackoff(restartSettings)(() => producer)
  }
  
  private def parseWithOffset[Result](msg: String, offset: CommittableOffset, parse: String => Result)(implicit ec: ExecutionContext): Future[Either[String, (Result, CommittableOffset)]] = 
    Utils.parseMessage(msg, msg => (parse(msg), offset))
    

  /*
  Η τελική μορφή των topics θα πρέπει να είναι κάπως έτσι:

  region1/building1/module1/temperature

  region1/building1/module1/energy

  κοκ.  (δες παρακάτω αναλυτικότερα)

  Η λογική του module είναι να περιγράψει οποιαδήποτε αυτοτελή δομή μέσα σε ένα κτίριο (πχ ένα διαμέρισμα, μια εταιρεία κτλ). 

  Αν έχουμε περισσότερες συσκευές που μετράνε το ίδιο δεδομένο μέσα σε ένα module (πχ περισσότερους αισθητήρες θερμοκρασίας σε ένα διαμέρισμα) 
  μπορούμε να επεκτείνουμε τα topics ως εξής:

  region1/building1/module1/module1[specific]/temperature , όπου module1[specific] μπορεί να είναι όροφος/δωμάτιο

  Αυτό μας επιτρέπει να κάνουμε subscribe σε όλα τα topics του κτιρίου ή του module αν θέλουμε, αλλά και να έχουμε πρόσβαση μόνο σε μια συσκευή.

  Μια αρχική προσέγγιση που θα μπορούσες να ακολουθήσεις είναι η δημιουργία 2 κτιρίων.

  Ένα κτίριο με ένα module όπου έχουμε: 
      ένα μετρητή ενέργειας(energy), 
      ένα μετρητή θερμοκρασίας/θερμοστάτη(temperature/thermostat), 
      μια έξυπνη πρίζα(plug), 
      έναν παραγωγό ενέργειας(producer)
      και μια μπαταρία(battery). 
      
  Για το κτίριο αυτό θα θέλαμε επομένως τα ακόλουθα topics:

  region1/building1/module1/temperature με payload {"value":25, "unit":"celsius", "timestamp":12345}                       προς manager
  region1/building1/module1/thermostat/command  με payload { "command" : "set", "value":20} (για έλεγχο του θερμοστάτη)    προς edge


  region1/building1/module1/energy με payload {"value":12.6,"unit":"kwh", "timestamp":12345}                                           προς manager
  region1/building1/module1/plug/energy με payload {"value":12.6,"unit":"kwh", "timestamp":12345}                                      προς manager

  region1/building1/module1/plug/command με payload {"command" : "plug_control","value":"stop"}   (για έλεγχο ενεργοβόρων συσκευών)     προς edge
  region1/building1/module1/plug/command με payload {"command" : "plug_control", "value":"start"} (για έλεγχο ενεργοβόρων συσκευών)     προς edge

  region1/building1/module1/producer/energy   με payload {"value":12.6,"unit":"kwh", "timestamp":12345}         προς manager
  region1/building1/module1/producer/command  με payload {"command":"producer_control" ,"value": "status" }   προς edge

  region1/building1/module1/producer/status  με payload {"command":"producer_control" ,"value": local_consumption/idle/grid_infusion " } προς manager
  region1/building1/module1/producer/command με payload {"command":"producer_control":"value":"infuse"}                                  προς edge         
  region1/building1/module1/producer/command με payload {"command":"producer_control":"value":"stop_infuse"}                             προς edge

  (
      για έλεγχο της φόρτισης. 
      Η λογική είναι ότι ως default φορτίζει η μπαταρία εκτός αν έρθει εξωτερική εντολή λόγο ανάγκης του συστήματος για ενέργεια. 
      Στην περίπτωση αυτή σταματά η φόρτιση και δίνεται ενέργεια στο δίκτυο. 
      Επομένως πρέπει να αλλάξει και το status της μπαταρίας
  )



  region1/building1/module1/battery/ status με payload {"percentage":50, "status":"charging/notcharging "}    προς manager
  region1/building1/module1/battery/command με payload { command:"request", "value":" status "}           προς edge


  Με τον ίδιο τρόπο δημιουργούμε και ένα δεύτερο κτίριο, αλλά χωρίς παραγωγή, με τα αντίστοιχα topics (energy + temperature + thermostat).

  Όσον αφορά την αρχιτεκτονική στο χαμηλό επίπεδο θα υπάρχουν δυο κλάσεις “low egde devices”:
      αυτές που θα συνοδεύονται από ένα rasperryPi (π.χ. οι έξυπνες που θα δέχονται και εντολές) 
      και αυτές που επικοινωνούν κατευθείαν με το παραπάνω layer.


  Θα χρειαστεί κάποιο πρωτόκολλο εγγραφής με το οποίο θα γνωστοποιεί την υπαρξή του μια συσκευή στο άκρο
  σε έναν iot manager, μέσω αυτού θα πρέπει να μπορεί να δημιουργηθεί ο αντίστοιχος actor που αντιπροσωπεύει
  τη συσκευή

  με το που λάβει πληροφορίες για μια νεα συσκευή 
  - μετατρέπει τα δεδομένα μορφής json αντίστοιχο αντικείμενο 
  - δημιουργεί το αντίστοιχο group αν δεν υπάρχει και 
  - στέλνει μήνυμα στον αντίστοιχο actor για τη δημιουργία νέας συσκευής

  η κάθε νέα συσκεύη θα πρέπει να γνωρίζει σε ποιό topic να κάνει εγγραφή και να στέλενει εντολές αν χρειαστεί
  (θα δίνεται ως παράμετρος κατα τη δημιουργία της)
    οι πληροφορίες για τα topic αυτά κατασκευάζονται απο τα στοιχεία του πρωτοκόλλου εγγραφής

  {
    id
    group
    module_path
    devicetype
  }

    [0] edge_device

      deviceActor
        subscribes: 
          [0] <-- [1] MQTT { /{buildingId}/register/{deviceId} , /{buildingId}/{modulePath}/{deviceId}/cmd }

        publishes:  
          [0] --> [1] MQTT { /{buildingId}/register/ , /{buildingId}/{modulePath}/{deviceId} } 

    [1] intermediate_manager

      deviceListener
        subscribes: 
          [0] --> [1] MQTT { /{buildingId}/register }
        
        publishes:  
          [0] <-- [1] MQTT { /{buildingId}/register/{deviceId} }
        
      deviceRepData
        subscribes: 
          [0] --> [1] MQTT { /{buildingId}/{modulePath}/{deviceId} }
        
        publishes:  
          [1] --> [2] KAFKA { Data-{buildingId} } 

      deviceRepCmd
        subscribes: 
          [1] <-- [2] KAFKA { {buildingId}-cmd-{deviceId} }
        
        publishes:  
          [0] <-- [1] MQTT { /{buildingId}/{modulePath}/{deviceId}/cmd }

      QueryConsumer
        subscribes: 
          [1] <-- [2] KAFKA { Query-{buildingId} }
        
        publishes:  
          [1] --> [2] KAFKA { Query-results-{buildingId} }

    [2] region_manager

      region-query-consumer 
        subscribes: 
          [2] <-- [3] KAFKA { Query-{regionId} }
        
        publishes:  
          [1] <-- [2] KAFKA { Query-{buildingId} }

      building-query-results-consumer 
        subscribes: 
          [1] --> [2] KAFKA { Query-results-{buildingId} }
        
        publishes:  
          [2] --> [3] KAFKA { ??? } 

      building-data-consumer 
        subscribes: 
          [1] --> [2] KAFKA { Data-{buildingId} }
        
        publishes:  
          [2] --> [3] KAFKA { ??? } 

      building-cmd-consumer 
        subscribes: 
          [2] <-- [3] KAFKA { {buildingId}-cmd  }
        
        publishes:  
          [1] <-- [2] KAFKA { {buildingId}-cmd-{id} }

  */
}
