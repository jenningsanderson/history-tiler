package org.heigit.bigspatialdata.oshdb;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.HashSet;

import com.google.common.collect.Sets;

import org.heigit.bigspatialdata.oshdb.api.db.OSHDBH2;
import org.heigit.bigspatialdata.oshdb.api.mapreducer.OSMContributionView;
import org.heigit.bigspatialdata.oshdb.api.object.OSMContribution;
import org.heigit.bigspatialdata.oshdb.osm.OSMEntity;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.util.OSHDBBoundingBox;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTag;
import org.heigit.bigspatialdata.oshdb.util.celliterator.ContributionType;
import org.heigit.bigspatialdata.oshdb.util.tagtranslator.OSMTag;
import org.heigit.bigspatialdata.oshdb.util.tagtranslator.TagTranslator;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.util.GeometricShapeFactory;

import static org.locationtech.jts.algorithm.Angle.angleBetween;
import static org.locationtech.jts.algorithm.Angle.toDegrees;

public class InteractionsBuilder {

    public static int NEW_OBJECTS = 0;
    public static int UPDATED_OBJECTS = 0;
    public static int MINOR_VERSION_CHANGE = 0;
    public static int DELETED_OBJECTS = 0;
    public static int MAJOR_GEOMETRY_CHANGE = 0;

    public static boolean PRINT = true;

    public static int count = 0;

    public static HashSet<Integer> mVUIDs = new HashSet<>();
    public static HashSet<Integer> majorVersionUIDs = new HashSet<>();

    public static void main(String[] args) {

//        if(args.length > 1){
//            System.out.println(args);
//        }else{
//            System.err.println("Please specify a database")
//        }

        Path h2Path = Paths.get("~/data/oshdb/planet-history.oshdb.mv.db");
//        Path h2Path = Paths.get("/Users/jenningsanderson/Desktop/foss4g_2019_ro_buce.oshdb.mv.db");
        try(OSHDBH2 oshdb = new OSHDBH2(h2Path.toString())){
            TagTranslator tagTranslator = new TagTranslator(oshdb.getConnection());

            //Turn on parallelization
            Stream<Integer> result = OSMContributionView.on(oshdb.multithreading(true))
//            Stream<Integer> result = OSMContributionView.on(oshdb)
//                    .areaOfInterest(new OSHDBBoundingBox(-180.0, -90.0, 180.0, 90))
//                    .areaOfInterest(new OSHDBBoundingBox(26.1152, -44.5023, 26.1154, 44.5024))
//                    .areaOfInterest(new OSHDBBoundingBox(-1.763966, -1.609479, 6.298851, 6.298851))
//                    .areaOfInterest(new OSHDBBoundingBox(83.951151,28.181861, 84.024995, 28.241129)) // Pokhara, Nepal
                  //westlimit=; southlimit=; eastlimit=; northlimit=
//                    .areaOfInterest(new OSHDBBoundingBox(83.9769,28.2122478921, 83.9805663895, 28.2146488456)) // Tiny Pokhara
                    .areaOfInterest(new OSHDBBoundingBox(-23.0,-48.4, 157.8, 37.8)) // most of the world... no euro and no na
//                    .timestamps("2015-04-25T00:00:00Z", "2015-06-01T00:00:00Z")
                    .osmType(OSMType.WAY)
//                    .osmTag("building")
                    .groupByEntity()
                    .map(contribs -> {

                        //Iterates through the contributions, sorted by timestamp ASC;

                        int idx = 0;
                        int minorVersionValue = 0;
                        long oneLaterContribTime = 0;

                        for( OSMContribution contrib : contribs) {

                            OSMEntity before = contrib.getEntityBefore();
                            OSMEntity after = contrib.getEntityAfter();

                            //If there is another contribution after this one, get that value.
                            if (idx==contribs.size()-1){
                                oneLaterContribTime = 0;
                            }else if (contribs.size() > (idx+1)){
                                oneLaterContribTime = contribs.get(idx+1).getTimestamp().getRawUnixTimestamp();
                            }

//                            if (contrib.getOSHEntity().getId()==341930865) {
//                                System.out.println(""+idx + "" + contrib.getEntityAfter().getVersion()+ " | " + oneLaterContribTime);
//                            }

                            if ( contrib.getContributionTypes().contains(ContributionType.CREATION) ){
                                NEW_OBJECTS++;
                                majorVersionUIDs.add(contrib.getContributorUserId());

                                try{
                                    String newObject = creation(contrib, oneLaterContribTime);
                                    if(PRINT){ System.out.print( newObject ); }
                                }catch (Exception e){
                                    System.err.println("CREATION COMPUTATION ERROR ON OBJECT: :" + contrib.getOSHEntity());
                                    e.printStackTrace();
                                }

                            } else if ( contrib.getContributionTypes().contains(ContributionType.DELETION) ) {
                                DELETED_OBJECTS++;
                                majorVersionUIDs.add(contrib.getContributorUserId());

                                try {
                                    if(PRINT){ System.out.print( deletion(contrib) ); }

                                } catch (Exception e) {
                                    System.err.println("DELETION COMPUTATION ERROR ON OBJECT: :" + contrib.getOSHEntity());
                                }

                            } else if (before.getVersion() == after.getVersion()) {

                                //If the version numbers are the same, then we have a potential minor version
                                try{
                                    if (yieldsMinorVersion(contrib)) {
                                        minorVersionValue++;
                                        MINOR_VERSION_CHANGE++;
                                        mVUIDs.add(contrib.getContributorUserId());
                                        String mV = minorVersion(contrib, oneLaterContribTime, minorVersionValue);
                                        if(PRINT){ System.out.print( mV ); }
                                    }

                                } catch (Exception e) {
                                    System.err.println("UNKNOWN ERROR ON OBJECT: :" + contrib.getOSHEntity());
                                }

                            } else {
                                //It's a major version, so reset the mV counter
                                minorVersionValue = 0;
                                // These are visible updates to the object that are visible on the map with versioning
                                UPDATED_OBJECTS++;
                                majorVersionUIDs.add(contrib.getContributorUserId());

                                if ( ( contrib.getContributionTypes().contains(ContributionType.TAG_CHANGE) &&
                                       contrib.getContributionTypes().contains(ContributionType.GEOMETRY_CHANGE) ) ){

                                    // TODO: A tag AND geometry change occurred

                                }else if ( contrib.getContributionTypes().contains(ContributionType.TAG_CHANGE ) ){

                                    // TODO: A tag change, what are some major tag changes we care about?
                                    for (OSHDBTag tag : after.getTags()) {
                                        int intKey = tag.getKey();
                                        int intVal = tag.getValue();
                                        OSMTag translated = tagTranslator.getOSMTagOf(tag);
                                        String strKey = translated.getKey();
                                        String strVal = translated.getValue();
                                    }
                                } else if (contrib.getContributionTypes().contains(ContributionType.GEOMETRY_CHANGE )){
                                    // TODO: A major geometry change occurred; what does this mean, exactly?

                                    try{
                                        String majorGeomChange = majorGeometry(contrib, oneLaterContribTime);
                                        if(PRINT){ System.out.print( majorGeomChange ); }

                                    } catch (Exception e) {
                                        System.err.println("Major Geometry Change Failure: " + contrib.getOSHEntity());
                                    }
                                }
                            }

                            // Save this contribution :)
                            oneLaterContribTime = contrib.getTimestamp().getRawUnixTimestamp();
                            idx++;
                        }
                        //need to actually return something here
                        return 1;

                    }).stream();

            result.forEach(s -> {
                //need to actually do something here to call it.
//                System.out.println("-----");
                if (count%100000==0){
                    System.err.print("\r"+(count/1000)+"k");
                }
                count += s;
            });

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.err.println("\nProcessed " + (count/1000) +  "K contributions");

        System.err.println("\nStatus: \n"+
                "\tNew objects....... "+ NEW_OBJECTS +"\n"+
                "\tUpdated Objects... "+ UPDATED_OBJECTS +"\n"+
                "\tMajor Geometries.. "+ MAJOR_GEOMETRY_CHANGE +"\n"+
                "\tMinor Versions.... "+ MINOR_VERSION_CHANGE +"\n"+
                "\tDeleted Objects... "+ DELETED_OBJECTS);


        System.err.println("========================");
        System.err.println("Minor version users: "+mVUIDs.size());
        System.err.println("Major version users: "+majorVersionUIDs.size());
        System.err.println("\tDifference: " + Sets.difference(mVUIDs,majorVersionUIDs).size());

    }

    public static boolean yieldsMinorVersion(OSMContribution contrib){
        Geometry before = contrib.getGeometryUnclippedBefore();
        Geometry after  = contrib.getGeometryUnclippedAfter();

        if (! before.equals(after) ) {
            return true;
        }else {
            return false;
        }
    }



    public static String creation(OSMContribution contrib, long oneLater){

        GeoJsonWriter writer = new GeoJsonWriter(18);
        writer.setEncodeCRS(false);

        Geometry newGeometry = contrib.getGeometryUnclippedAfter();

        return "{\"type\":\"Feature\",\"properties\":{"+
                "\"@e\":\"CRE\"," +
                "\"@uid\":" + contrib.getContributorUserId() + "," +
                "\"@id\":" + contrib.getOSHEntity().getId() + "," +
                "\"@c\":" + contrib.getChangesetId() + "," +
                "\"@vS\":" + contrib.getTimestamp().getRawUnixTimestamp() + "," +
                "\"@vU\":" + ( (oneLater == 0) ? null : oneLater )+ "}," +
                "\"geometry\":" + writer.write(newGeometry) + "}\n";
    }


    public static String deletion(OSMContribution contrib){

        GeoJsonWriter writer = new GeoJsonWriter(18);
        writer.setEncodeCRS(false);

        Geometry beforeGeometry = contrib.getGeometryUnclippedBefore();

        return "{\"type\":\"Feature\",\"properties\":{"+
                "\"@e\":\"DEL\"," +
                "\"@uid\":" + contrib.getContributorUserId() + "," +
                "\"@duid\":" + contrib.getEntityBefore().getUserId() + "," +
                "\"@id\":" + contrib.getOSHEntity().getId() + "," +
                "\"@c\":" + contrib.getChangesetId() + "," +
                "\"@vS\":" + contrib.getEntityBefore().getTimestamp().getRawUnixTimestamp() +"," +
                "\"@vU\":" + contrib.getTimestamp().getRawUnixTimestamp() + "}," +
                "\"geometry\":" + writer.write(beforeGeometry) + "}\n";
    }

    public static String minorVersion(OSMContribution contrib, long oneLater, int mV){

        GeoJsonWriter writer = new GeoJsonWriter(18);
                      writer.setEncodeCRS(false);

        DecimalFormat numberFormat = new DecimalFormat("0.0000");

        String sq = "";
        if (contrib.getGeometryUnclippedAfter().getGeometryType().contains("Polygon") ){
            sq = "\"@sq\":"+  numberFormat.format( avgSquareOffsetProjected(contrib.getGeometryUnclippedAfter()) - avgSquareOffsetProjected(contrib.getGeometryUnclippedBefore()) )+",";
        }

        return "{\"type\":\"Feature\",\"properties\":{"+
                        "\"@e\":\"MV\"," +
                        "\"@mV\":"+ mV + "," +
                        "\"@uid\":" + contrib.getContributorUserId() + "," +
                        "\"@id\":" + contrib.getOSHEntity().getId() + "," +
                        "\"@c\":" + contrib.getChangesetId() + "," + sq +
                        "\"@vS\":" + contrib.getTimestamp().getRawUnixTimestamp() + "," +
                        "\"@vU\":" + ( (oneLater == 0) ? null : oneLater )+ "}," +
                        "\"geometry\":" + writer.write(contrib.getGeometryUnclippedAfter()) + "}\n";

//               "{\"type\":\"Feature\",\"properties\":{"+
//                        "\"@e\":\"MV_BEFORE\"," +
//                        "\"@uid\":" + contrib.getEntityBefore().getUserId() + "," +
//                        "\"@id\":" + contrib.getOSHEntity().getId() + "," +
//                        "\"@vS\":" + contrib.getEntityBefore().getTimestamp().getRawUnixTimestamp() + "," +
//                        "\"@vU\":" + contrib.getTimestamp().getRawUnixTimestamp() + "}," +
//                        "\"geometry\":" + writer.write(before) + "}\n" +

    }

    public static String majorGeometry(OSMContribution contrib, long oneLater){

        Geometry before = contrib.getGeometryUnclippedBefore();
        Geometry after  = contrib.getGeometryUnclippedAfter();

        GeoJsonWriter writer = new GeoJsonWriter(18);
        writer.setEncodeCRS(false);

        DecimalFormat numberFormat = new DecimalFormat("0.0000");

        if (! before.equals(after) ) {

            MAJOR_GEOMETRY_CHANGE++;

            String sq = "";
            if (contrib.getGeometryUnclippedAfter().getGeometryType().contains("Polygon") ){
                sq = "\"@sq\":"+ numberFormat.format( ( avgSquareOffsetProjected(after) - avgSquareOffsetProjected(before) ) )+",";
            }

            return
//                    "{\"type\":\"Feature\",\"properties\":{"+
//                            "\"@e\":\"MG_BEFORE\"," +
//                            "\"@uid\":" + contrib.getEntityBefore().getUserId() + "," +
//                            "\"@id\":" + contrib.getOSHEntity().getId() + "," +
//                            "\"@vS\":" + contrib.getEntityBefore().getTimestamp().getRawUnixTimestamp() + "," +
//                            "\"@vU\":" + contrib.getTimestamp().getRawUnixTimestamp() + "}," +
//                            "\"geometry\":" + writer.write(before) + "}\n" +

                            "{\"type\":\"Feature\",\"properties\":{"+
                            "\"@e\":\"MG\"," + sq +
                            "\"@c\":" + contrib.getChangesetId() +","+
                            "\"@uid\":" + contrib.getContributorUserId() + "," +
                            "\"@id\":" + contrib.getOSHEntity().getId() + "," +
                            "\"@vS\":" + contrib.getTimestamp().getRawUnixTimestamp() + "," +
                            "\"@vU\":" + ( (oneLater == 0) ? null : oneLater )+ "}," +
                            "\"geometry\":" + writer.write(after) + "}\n";
        }else{
            return "";
        }
    }

    private static Coordinate projectToSphere(Coordinate coord){
        Deg2UTM coord2 = new Deg2UTM(coord.getY(), coord.getX());

        return new Coordinate(coord2.Easting,coord2.Northing,0.);
    }


    public static double avgSquareOffsetProjected(Geometry geom){

        Coordinate[] corners = geom.getCoordinates();

        if (corners.length > 2) {
            ArrayList<Double> cornerAngles = new ArrayList<Double>();
            for (int i = 2; i < corners.length; i++) {

                cornerAngles.add(toDegrees(angleBetween(projectToSphere( corners[i - 2] ), projectToSphere( corners[i - 1]) , projectToSphere( corners[i]))));
            }
            cornerAngles.add(toDegrees(angleBetween(projectToSphere( corners[corners.length - 2]), projectToSphere(corners[0]), projectToSphere( corners[1])))); //Remember, if it's closed -1 == 0;

            double avgError = 0;
            for (Double angle : cornerAngles) {
                //If the angle is closer to 180 than 90, then mod 90;
                if (angle > 135) {
                    angle = angle % 90;
                }
                avgError += Math.abs(angle - 90);
            }
            avgError /= cornerAngles.size();
//            GeoJsonWriter writer = new GeoJsonWriter(18);
//            writer.setEncodeCRS(false);
//            System.out.println(writer.write(geom) + "\nProj:" + avgError + " | " + cornerAngles);

            return avgError;

        }else{
            return 100;
        }
    }

    //https://stackoverflow.com/questions/176137/java-convert-lat-lon-to-utm
    private static class Deg2UTM
    {
        double Easting;
        double Northing;
        int Zone;
        char Letter;
        private  Deg2UTM(double Lat,double Lon)
        {
            Zone= (int) Math.floor(Lon/6+31);
            if (Lat<-72)
                Letter='C';
            else if (Lat<-64)
                Letter='D';
            else if (Lat<-56)
                Letter='E';
            else if (Lat<-48)
                Letter='F';
            else if (Lat<-40)
                Letter='G';
            else if (Lat<-32)
                Letter='H';
            else if (Lat<-24)
                Letter='J';
            else if (Lat<-16)
                Letter='K';
            else if (Lat<-8)
                Letter='L';
            else if (Lat<0)
                Letter='M';
            else if (Lat<8)
                Letter='N';
            else if (Lat<16)
                Letter='P';
            else if (Lat<24)
                Letter='Q';
            else if (Lat<32)
                Letter='R';
            else if (Lat<40)
                Letter='S';
            else if (Lat<48)
                Letter='T';
            else if (Lat<56)
                Letter='U';
            else if (Lat<64)
                Letter='V';
            else if (Lat<72)
                Letter='W';
            else
                Letter='X';
            Easting=0.5*Math.log((1+Math.cos(Lat*Math.PI/180)*Math.sin(Lon*Math.PI/180-(6*Zone-183)*Math.PI/180))/(1-Math.cos(Lat*Math.PI/180)*Math.sin(Lon*Math.PI/180-(6*Zone-183)*Math.PI/180)))*0.9996*6399593.62/Math.pow((1+Math.pow(0.0820944379, 2)*Math.pow(Math.cos(Lat*Math.PI/180), 2)), 0.5)*(1+ Math.pow(0.0820944379,2)/2*Math.pow((0.5*Math.log((1+Math.cos(Lat*Math.PI/180)*Math.sin(Lon*Math.PI/180-(6*Zone-183)*Math.PI/180))/(1-Math.cos(Lat*Math.PI/180)*Math.sin(Lon*Math.PI/180-(6*Zone-183)*Math.PI/180)))),2)*Math.pow(Math.cos(Lat*Math.PI/180),2)/3)+500000;
            Easting=Math.round(Easting*100)*0.01;
            Northing = (Math.atan(Math.tan(Lat*Math.PI/180)/Math.cos((Lon*Math.PI/180-(6*Zone -183)*Math.PI/180)))-Lat*Math.PI/180)*0.9996*6399593.625/Math.sqrt(1+0.006739496742*Math.pow(Math.cos(Lat*Math.PI/180),2))*(1+0.006739496742/2*Math.pow(0.5*Math.log((1+Math.cos(Lat*Math.PI/180)*Math.sin((Lon*Math.PI/180-(6*Zone -183)*Math.PI/180)))/(1-Math.cos(Lat*Math.PI/180)*Math.sin((Lon*Math.PI/180-(6*Zone -183)*Math.PI/180)))),2)*Math.pow(Math.cos(Lat*Math.PI/180),2))+0.9996*6399593.625*(Lat*Math.PI/180-0.005054622556*(Lat*Math.PI/180+Math.sin(2*Lat*Math.PI/180)/2)+4.258201531e-05*(3*(Lat*Math.PI/180+Math.sin(2*Lat*Math.PI/180)/2)+Math.sin(2*Lat*Math.PI/180)*Math.pow(Math.cos(Lat*Math.PI/180),2))/4-1.674057895e-07*(5*(3*(Lat*Math.PI/180+Math.sin(2*Lat*Math.PI/180)/2)+Math.sin(2*Lat*Math.PI/180)*Math.pow(Math.cos(Lat*Math.PI/180),2))/4+Math.sin(2*Lat*Math.PI/180)*Math.pow(Math.cos(Lat*Math.PI/180),2)*Math.pow(Math.cos(Lat*Math.PI/180),2))/3);
            if (Letter<'M')
                Northing = Northing + 10000000;
            Northing=Math.round(Northing*100)*0.01;
        }
    }
}




/*

    public static double avgSquareOffset(Geometry geom){

        Coordinate[] corners = geom.getCoordinates();

        if (corners.length > 2) {
            ArrayList<Double> cornerAngles = new ArrayList<Double>();
            for (int i = 2; i < corners.length; i++) {

                cornerAngles.add(toDegrees(angleBetween(corners[i - 2], corners[i - 1], corners[i])));
            }
            cornerAngles.add(toDegrees(angleBetween(corners[corners.length - 2], corners[0], corners[1]))); //Remember, if it's closed -1 == 0;

            double avgError = 0;
            for (Double angle : cornerAngles) {
                //If the angle is closer to 180 than 90, then mod 90;
                if (angle > 135) {
                    angle = angle % 90;
                }
                avgError += Math.abs(angle - 90);
            }
            avgError /= cornerAngles.size();
            GeoJsonWriter writer = new GeoJsonWriter(18);
            writer.setEncodeCRS(false);
            System.out.println(writer.write(geom) + "\n" + avgError + " | " + cornerAngles);

            return avgError;

        }else{
            return 100;
        }
    }
 */
