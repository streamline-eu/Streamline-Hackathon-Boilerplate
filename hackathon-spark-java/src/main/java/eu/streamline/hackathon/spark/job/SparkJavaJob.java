package eu.streamline.hackathon.spark.job;

import eu.streamline.hackathon.common.data.GDELTEvent;
import eu.streamline.hackathon.spark.scala.operations.GDELTInputReceiver;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.Optional;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.Function3;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Minutes;
import org.apache.spark.streaming.State;
import org.apache.spark.streaming.StateSpec;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import scala.Tuple2;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;

/**
 * @author behrouz
 */
public class SparkJavaJob {

    public static void main(String[] args) throws InterruptedException {

        ParameterTool params = ParameterTool.fromArgs(args);
        final String pathToGDELT = params.get("path");
        final Long duration = params.getLong("micro-batch-duration", 1000);
        final String country = params.get("country", "USA");

        SparkConf conf = new SparkConf().setAppName("Spark Java GDELT Analyzer");
        String masterURL = conf.get("spark.master", "local[*]");
        conf.setMaster(masterURL);

        JavaStreamingContext jssc = new JavaStreamingContext(conf, new Duration(duration));
        // checkpoint for storing the state
        jssc.checkpoint("checkpoint/");

        // function to store intermediate values in the state
        // it is called in the mapWithState function of DStream
        Function3<Date, Optional<Double>, State<Double>, Tuple2<Date, Double>> mappingFunc =
                new Function3<Date, Optional<Double>, State<Double>, Tuple2<Date, Double>>() {
                    @Override
                    public Tuple2<Date, Double> call(Date weekOfYear, Optional<Double> avgTone, State<Double> state) throws Exception {
                        Double sum = avgTone.orElse(0.0) + (state.exists() ? state.get() : 0.0);
                        Tuple2<Date, Double> output = new Tuple2<>(weekOfYear, sum);
                        state.update(sum);
                        return output;
                    }
                };

        jssc
                .receiverStream(new GDELTInputReceiver(pathToGDELT))
                .filter(new Function<GDELTEvent, Boolean>() {
                    @Override
                    public Boolean call(GDELTEvent gdeltEvent) throws Exception {
                        return Objects.equals(gdeltEvent.actor1Code_countryCode, country);
                    }
                })
                .mapToPair(new PairFunction<GDELTEvent, Date, Double>() {
                    @Override
                    public Tuple2<Date, Double> call(GDELTEvent gdeltEvent) throws Exception {
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(gdeltEvent.dateAdded);
                        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                        return new Tuple2<>(cal.getTime(), gdeltEvent.avgTone);
                    }
                })
                .reduceByKey(new Function2<Double, Double, Double>() {
                    @Override
                    public Double call(Double one, Double two) throws Exception {
                        return one + two;
                    }
                })
                .mapWithState(StateSpec.function(mappingFunc))
                .map(new Function<Tuple2<Date, Double>, String>() {
                    @Override
                    public String call(Tuple2<Date, Double> event) throws Exception {
                        DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                        return "Country(" + country + "), Week(" + format.format(event._1()) + "), " +
                                "AvgTone(" + event._2() + ")";

                    }
                })
                .print();

        jssc.start();
        jssc.awaitTermination();

    }
}
