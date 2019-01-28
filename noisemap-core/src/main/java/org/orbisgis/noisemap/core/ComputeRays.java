/**
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 * <p>
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 * <p>
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 * <p>
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 * <p>
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p>
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 * <p>
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.orbisgis.noisemap.core;


import org.h2gis.api.ProgressVisitor;
import org.h2gis.utilities.jts_utils.CoordinateUtils;
import org.locationtech.jts.algorithm.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.orbisgis.noisemap.core.FastObstructionTest.Wall;


/**
 * @author Nicolas Fortin
 * @author Pierre Aumond
 */
public class ComputeRays implements Runnable {
    private final static double BASE_LVL = 1.; // 0dB lvl
    private final static double ONETHIRD = 1. / 3.;
    private final static double MERGE_SRC_DIST = 1.;
    private final static double FIRST_STEP_RANGE = 90;
    private final static double W_RANGE = Math.pow(10, 94. / 10.); //94 dB(A) range search. Max iso level is >75 dB(a).
    private Thread thread;
    private PropagationProcessData data;
    private ComputeRaysOut dataOut;
    private Quadtree cornersQuad;
    private int nbfreq;
    private long diffractionPathCount = 0;
    private long refpathcount = 0;
    private double[] alpha_atmo;
    private double[] freq_lambda;
    // todo implement this next variable as input parameter
    private double gS = 0; // 0 si route, 1 si ballast

    private STRtree rTreeOfGeoSoil;
    private final static Logger LOGGER = LoggerFactory.getLogger(ComputeRays.class);


    private static double GetGlobalLevel(int nbfreq, double energeticSum[]) {
        double globlvl = 0;
        for (int idfreq = 0; idfreq < nbfreq; idfreq++) {
            globlvl += energeticSum[idfreq];
        }
        return globlvl;
    }

    public ComputeRays(PropagationProcessData data,
                       ComputeRaysOut dataOut) {
        thread = new Thread(this);
        this.dataOut = dataOut;
        this.data = data;
    }

    /**
     * Update ground Z coordinates of sound sources and receivers absolute to sea levels
     */
    public void makeRelativeZToAbsolute() {
        AbsoluteCoordinateSequenceFilter filter = new AbsoluteCoordinateSequenceFilter(data.freeFieldFinder);
        for (Geometry source : data.sourceGeometries) {
            source.apply(filter);
        }
        CoordinateSequence sequence = new CoordinateArraySequence(data.receivers.toArray(new Coordinate[data.receivers.size()]));
        for (int i = 0; i < sequence.size(); i++) {
            filter.filter(sequence, i);
        }
        data.receivers = Arrays.asList(sequence.toCoordinateArray());
    }

    public void start() {
        thread.start();
    }

    public void join() {
        try {
            thread.join();
        } catch (Exception e) {
            return;
        }
    }

    public static double dbaToW(double dBA) {
        return Math.pow(10., dBA / 10.);
    }

    public static double wToDba(double w) {
        return 10 * Math.log10(w);
    }

    /**
     * @param startPt Compute the closest point on lineString with this coordinate,
     *                use it as one of the splitted points
     * @return computed delta
     */
    private double splitLineStringIntoPoints(Geometry geom, Coordinate startPt,
                                             List<Coordinate> pts, double minRecDist) {
        // Find the position of the closest point
        Coordinate[] points = geom.getCoordinates();
        // For each segments
        Double bestClosestPtDist = Double.MAX_VALUE;
        Coordinate closestPt = null;
        double roadLength = 0.;
        for (int i = 1; i < points.length; i++) {
            LineSegment seg = new LineSegment(points[i - 1], points[i]);
            roadLength += seg.getLength();
            Coordinate ptClosest = seg.closestPoint(startPt);
            // Interpolate Z value
            ptClosest.setOrdinate(2, Vertex.interpolateZ(ptClosest, seg.p0, seg.p1));
            double closestDist = CGAlgorithms3D.distance(startPt, ptClosest);
            if (closestDist < bestClosestPtDist) {
                bestClosestPtDist = closestDist;
                closestPt = ptClosest;
            }
        }
        if (closestPt == null) {
            return 1.;
        }
        double delta = 20.;
        // If the minimum effective distance between the line source and the
        // receiver is smaller than the minimum distance constraint then the
        // discretization parameter is changed
        // Delta must not not too small to avoid memory overhead.
        if (bestClosestPtDist < minRecDist) {
            bestClosestPtDist = minRecDist;
        }
        if (bestClosestPtDist / 2 < delta) {
            delta = bestClosestPtDist / 2;
        }
        pts.add(closestPt);
        Coordinate[] splitedPts = JTSUtility
                .splitMultiPointsInRegularPoints(points, delta);
        for (Coordinate pt : splitedPts) {
            if (pt.distance(closestPt) > delta) {
                pts.add(pt);
            }
        }
        if (delta < roadLength) {
            return delta;
        } else {
            return roadLength;
        }
    }


    public PropagationPath computeReflexion(Coordinate receiverCoord,
                                            Coordinate srcCoord, boolean favorable, List<Wall> nearBuildingsWalls,
                                            List<PropagationDebugInfo> debugInfo) {
        // Compute receiver mirror
        LineSegment srcReceiver = new LineSegment(srcCoord, receiverCoord);
        LineIntersector linters = new RobustLineIntersector();
        long imageReceiver = 0;

        double altR = 0;
        double altS = 0;
        Coordinate lastPoint = new Coordinate();
        Coordinate projSource;
        GeometryFactory factory = new GeometryFactory();
        double gPath = 0;
        double totRSDistance = 0.;


        List<PropagationPath.PointPath> points = new ArrayList<PropagationPath.PointPath>();
        List<PropagationPath.SegmentPath> segments = new ArrayList<PropagationPath.SegmentPath>();
        List<PropagationPath.SegmentPath> srPath = new ArrayList<PropagationPath.SegmentPath>();
        Coordinate reflectionPt = new Coordinate();
        PropagationPath propagationPath = new PropagationPath();


        MirrorReceiverIterator.It mirroredReceivers = new MirrorReceiverIterator.It(receiverCoord, nearBuildingsWalls,
                srcReceiver, data.maxRefDist, data.reflexionOrder, data.maxSrcDist);

        for (MirrorReceiverResult receiverReflection : mirroredReceivers) {
            double ReflectedSrcReceiverDistance = receiverReflection.getReceiverPos().distance(srcCoord);

            imageReceiver++;
            boolean validReflection = false;
            double reflectionAlpha = 1;
            MirrorReceiverResult receiverReflectionCursor = receiverReflection;
            // Test whether intersection point is on the wall
            // segment or not
            Coordinate destinationPt = new Coordinate(srcCoord);

            Wall seg = nearBuildingsWalls
                    .get(receiverReflection.getWallId());
            linters.computeIntersection(seg.p0, seg.p1,
                    receiverReflection.getReceiverPos(),
                    destinationPt);
            PropagationDebugInfo propagationDebugInfo = null;
            if (debugInfo != null) {
                propagationDebugInfo = new PropagationDebugInfo(new LinkedList<>(Arrays.asList(srcCoord)), new double[data.freq_lvl.size()]);
            }
            // While there is a reflection point on another wall. And intersection point is in the wall z bounds.
            reflectionPt = new Coordinate(
                    linters.getIntersection(0));
            while (linters.hasIntersection() && MirrorReceiverIterator.wallPointTest(seg, destinationPt)) {
                // There are a probable reflection point on the
                // segment

                reflectionPt = addBuffer(reflectionPt,destinationPt,true);

                // Compute Z interpolation
                reflectionPt.setOrdinate(Coordinate.Z, Vertex.interpolateZ(linters.getIntersection(0),
                        receiverReflectionCursor.getReceiverPos(), destinationPt));

                // Test if there is no obstacles between the
                // reflection point and old reflection pt (or source position)
                validReflection = (Double.isNaN(receiverReflectionCursor.getReceiverPos().z) ||
                        Double.isNaN(destinationPt.z) || seg.getBuildingId() == 0
                        || reflectionPt.z < data.freeFieldFinder.getBuildingRoofZ(seg.getBuildingId()));
                if (validReflection) // Reflection point can see
                // source or its image
                // source or its image
                {

                    if (propagationDebugInfo != null) {
                        propagationDebugInfo.getPropagationPath().add(0, reflectionPt);
                    }
                    if (receiverReflectionCursor
                            .getParentMirror() == null) { // Direct to the receiver
                        validReflection = data.freeFieldFinder
                                .isFreeField(reflectionPt,
                                        receiverCoord);
                        break; // That was the last reflection
                    } else {
                        // There is another reflection
                        destinationPt.setCoordinate(reflectionPt);
                        // Move reflection information cursor to a
                        // reflection closer
                        receiverReflectionCursor = receiverReflectionCursor.getParentMirror();
                        // Update intersection data
                        seg = nearBuildingsWalls
                                .get(receiverReflectionCursor
                                        .getWallId());
                        linters.computeIntersection(seg.p0, seg.p1,
                                receiverReflectionCursor
                                        .getReceiverPos(),
                                destinationPt
                        );
                        validReflection = false;
                    }
                } else {
                    break;
                }
            }
            if (validReflection) {
                if (propagationDebugInfo != null) {
                    propagationDebugInfo.getPropagationPath().add(0, receiverCoord);
                }
                lastPoint = reflectionPt;

                // A path has been found
                refpathcount += 1;
                List<PropagationPath> propagationPaths = directPath(destinationPt,reflectionPt,nearBuildingsWalls,false, debugInfo);
                propagationPath = propagationPaths.get(0);
                propagationPath.getPointList().get(propagationPath.getPointList().size()-1).setType(PropagationPath.PointPath.POINT_TYPE.REFL);
                propagationPath.getPointList().get(propagationPath.getPointList().size()-1).setBuildingId(receiverReflection.getBuildingId());
                if (refpathcount > 1) {
                    propagationPath.getPointList().remove(0);
                }
                points.addAll(propagationPath.getPointList());
                segments.addAll(propagationPath.getSegmentList());


                if (propagationDebugInfo != null && debugInfo != null) {
                    debugInfo.add(propagationDebugInfo);
                }


            }
        }
        if (refpathcount > 0) {
            List<PropagationPath> propagationPaths = directPath(lastPoint,receiverCoord,nearBuildingsWalls,false, debugInfo);
            propagationPath = propagationPaths.get(0);
            propagationPath.getPointList().remove(0);
            points.addAll(propagationPath.getPointList());
            segments.addAll(propagationPath.getSegmentList());


            for (int i = 1; i< points.size();i++){
                if (points.get(i).type == PropagationPath.PointPath.POINT_TYPE.REFL){
                    points.get(i).coordinate.z = Vertex.interpolateZ(points.get(i).coordinate,points.get(i-1).coordinate, points.get(i+1).coordinate);
                    //check if in building
                    if (points.get(i).coordinate.z > data.freeFieldFinder.getBuildingRoofZ(points.get(i).getBuildingId())){
                        points.clear();
                        segments.clear();
                    }

                }
            }


        }
        this.dataOut.appendImageReceiver(imageReceiver);
        return new PropagationPath(favorable, points, segments, srPath);
    }


    public double computeDeltaDiffraction(int idfreq, double eLength, double deltaDistance, double h0) {
        double cprime;
        //C" NMPB 2008 P.33
        //Multiple diffraction
        //CPRIME=( 1+(5*gamma)^2)/((1/3)+(5*gamma)^2)
        double gammaPart = Math.pow((5 * freq_lambda[idfreq]) / eLength, 2);
        double Ch = Math.min(h0 * (data.celerity / freq_lambda[idfreq]) / 250, 1);
        //NFS 31-133 page 46
        if (eLength > 0.3) {
            cprime = (1. + gammaPart) / (ONETHIRD + gammaPart);
        } else {
            cprime = 1.;
        }

        //(7.11) NMP2008 P.32
        double testForm = (40 / freq_lambda[idfreq])
                * cprime * deltaDistance;
        double diffractionAttenuation = 0.;
        if (testForm >= -2.) {
            diffractionAttenuation = 10 * Ch * Math
                    .log10(3 + testForm);
        }
        // Limit to 0<=DiffractionAttenuation
        diffractionAttenuation = Math.max(0,
                diffractionAttenuation);

        return diffractionAttenuation;
    }

    private static List<Coordinate> removeDuplicates(List<Coordinate> coordinates) {
        return Arrays.asList(CoordinateUtils.removeDuplicatedCoordinates(
                coordinates.toArray(new Coordinate[coordinates.size()]), false));
    }

    private double[] computeDeltaDistance(DiffractionWithSoilEffetZone diffDataWithSoilEffet) {
        final Coordinate srcCoord = diffDataWithSoilEffet.getOSZone().getCoordinate(1);
        final Coordinate receiverCoord = diffDataWithSoilEffet.getROZone().getCoordinate(0);
        final LineSegment OSZone = diffDataWithSoilEffet.getOSZone();
        final LineSegment ROZone = diffDataWithSoilEffet.getROZone();
        final double fulldistance = diffDataWithSoilEffet.getFullDiffractionDistance();
        // R' is the projection of R on the mean ground plane (O,R)
        List<Coordinate> planeCoordinates = new ArrayList<>(diffDataWithSoilEffet.getrOgroundCoordinates());
        planeCoordinates.addAll(diffDataWithSoilEffet.getoSgroundCoordinates());
        planeCoordinates = JTSUtility.getNewCoordinateSystem(planeCoordinates);
        List<Coordinate> rOPlaneCoordinates = planeCoordinates.subList(0, diffDataWithSoilEffet.getrOgroundCoordinates().size());
        List<Coordinate> oSPlaneCoordinates = planeCoordinates.subList(rOPlaneCoordinates.size(), planeCoordinates.size());
        // Compute source position using new plane system
        Coordinate rotatedSource = new Coordinate(oSPlaneCoordinates.get(oSPlaneCoordinates.size() - 1));
        rotatedSource.setOrdinate(1, srcCoord.z);
        Coordinate rotatedOs = new Coordinate(oSPlaneCoordinates.get(0));
        rotatedOs.setOrdinate(1, OSZone.getCoordinate(0).z);
        // Compute receiver position using new plane system
        Coordinate rotatedReceiver = new Coordinate(rOPlaneCoordinates.get(0));
        rotatedReceiver.setOrdinate(1, receiverCoord.z);
        Coordinate rotatedOr = new Coordinate(rOPlaneCoordinates.get(rOPlaneCoordinates.size() - 1));
        rotatedOr.setOrdinate(1, ROZone.getCoordinate(1).z);
        // Compute mean ground plane
        final double[] oSFuncParam = JTSUtility.getLinearRegressionPolyline(removeDuplicates(oSPlaneCoordinates));
        final double[] rOFuncParam = JTSUtility.getLinearRegressionPolyline(removeDuplicates(rOPlaneCoordinates));
        // Compute source and receiver image on ground
        Coordinate rPrim = JTSUtility.makePointImage(rOFuncParam[0], rOFuncParam[1], rotatedReceiver);
        Coordinate sPrim = JTSUtility.makePointImage(oSFuncParam[0], oSFuncParam[1], rotatedSource);
        double deltaDistanceORprim = (fulldistance - CGAlgorithms3D.distance(ROZone.p0, ROZone.p1)
                + rPrim.distance(rotatedOr)) - rPrim.distance(rotatedSource);
        // S' is the projection of R on the mean ground plane (S,O)
        double deltaDistanceSprimO = (fulldistance - CGAlgorithms3D.distance(OSZone.p0, OSZone.p1)
                + sPrim.distance(rotatedOs)) - sPrim.distance(rotatedReceiver);
        return new double[]{deltaDistanceSprimO, deltaDistanceORprim};
    }


    private double[] computeCoordprime(DiffractionWithSoilEffetZone diffDataWithSoilEffet, boolean obstructedSourceReceiver) {
        final Coordinate srcCoord = diffDataWithSoilEffet.getOSZone().getCoordinate(1);
        final Coordinate receiverCoord = diffDataWithSoilEffet.getROZone().getCoordinate(0);
        final LineSegment OSZone = diffDataWithSoilEffet.getOSZone();
        final LineSegment ROZone = diffDataWithSoilEffet.getROZone();
        final double fulldistance = diffDataWithSoilEffet.getFullDiffractionDistance();
        // R' is the projection of R on the mean ground plane (O,R)
        List<Coordinate> planeCoordinates = new ArrayList<>(diffDataWithSoilEffet.getrOgroundCoordinates());
        planeCoordinates.addAll(diffDataWithSoilEffet.getoSgroundCoordinates());
        planeCoordinates = JTSUtility.getNewCoordinateSystem(planeCoordinates);
        List<Coordinate> rOPlaneCoordinates = planeCoordinates.subList(0, diffDataWithSoilEffet.getrOgroundCoordinates().size());
        List<Coordinate> oSPlaneCoordinates = planeCoordinates.subList(rOPlaneCoordinates.size(), planeCoordinates.size());
        // Compute source position using new plane system
        Coordinate rotatedSource = new Coordinate(oSPlaneCoordinates.get(oSPlaneCoordinates.size() - 1));
        rotatedSource.setOrdinate(1, srcCoord.z);
        Coordinate rotatedOs = new Coordinate(oSPlaneCoordinates.get(0));
        rotatedOs.setOrdinate(1, OSZone.getCoordinate(0).z);
        // Compute receiver position using new plane system
        Coordinate rotatedReceiver = new Coordinate(rOPlaneCoordinates.get(0));
        rotatedReceiver.setOrdinate(1, receiverCoord.z);
        Coordinate rotatedOr = new Coordinate(rOPlaneCoordinates.get(rOPlaneCoordinates.size() - 1));
        rotatedOr.setOrdinate(1, ROZone.getCoordinate(1).z);
        // Compute mean ground plane
        final double[] oSFuncParam = JTSUtility.getLinearRegressionPolyline(removeDuplicates(oSPlaneCoordinates));
        final double[] rOFuncParam = JTSUtility.getLinearRegressionPolyline(removeDuplicates(rOPlaneCoordinates));
        // Compute source and receiver image on ground
        Coordinate rPrim = JTSUtility.makePointImage(rOFuncParam[0], rOFuncParam[1], rotatedReceiver);
        Coordinate sPrim = JTSUtility.makePointImage(oSFuncParam[0], oSFuncParam[1], rotatedSource);

        rPrim.setOrdinate(0, receiverCoord.x - rPrim.x); // todo check that !!
        sPrim.setOrdinate(0, receiverCoord.x - sPrim.x); // todo check that !!
        rPrim.setOrdinate(2, rPrim.y);
        sPrim.setOrdinate(2, sPrim.y);
        rPrim.setOrdinate(1, receiverCoord.y);
        sPrim.setOrdinate(1, srcCoord.y);

        DiffractionWithSoilEffetZone diffDataWithSoilEffetSprime = data.freeFieldFinder.getPath(receiverCoord, sPrim);
        DiffractionWithSoilEffetZone diffDataWithSoilEffetRprime = data.freeFieldFinder.getPath(rPrim, srcCoord);
        final double DeltaDistanceSp = diffDataWithSoilEffetSprime.getDeltaDistance();
        final double DeltaDistanceRp = diffDataWithSoilEffetRprime.getDeltaDistance();
        final double DeltaDistanceSpfav = diffDataWithSoilEffetSprime.getDeltaDistancefav();
        final double DeltaDistanceRpfav = diffDataWithSoilEffetRprime.getDeltaDistancefav();
        if (obstructedSourceReceiver) {
            return new double[]{DeltaDistanceSp, DeltaDistanceRp, DeltaDistanceSpfav, DeltaDistanceRpfav};
        } else {
            return new double[]{-DeltaDistanceSp, -DeltaDistanceRp, -DeltaDistanceSpfav, -DeltaDistanceRpfav};
        }
    }


    /**
     *
     * @param receiverCoord
     * @param srcCoord
     * @param debugInfo
     */
    public PropagationPath computeFreefield(Coordinate receiverCoord,
                                            Coordinate srcCoord, boolean favorable,
                                            List<PropagationDebugInfo> debugInfo) {

        GeometryFactory factory = new GeometryFactory();
        List<PropagationPath.PointPath> points = new ArrayList<PropagationPath.PointPath>();
        List<PropagationPath.SegmentPath> segments = new ArrayList<PropagationPath.SegmentPath>();
        List<PropagationPath.SegmentPath> srPath = new ArrayList<PropagationPath.SegmentPath>();
        List<PropagationPath> propagationPaths = new ArrayList<PropagationPath>();

        double gPath = 0;
        double totRSDistance = 0.;
        double altR = 0;
        double altS = 0;
        Coordinate projReceiver;
        Coordinate projSource;

        //will give a flag here for soil effect
        if (data.geoWithSoilType != null) {
            LineString RSZone = factory.createLineString(new Coordinate[]{receiverCoord, srcCoord});
            List<EnvelopeWithIndex<Integer>> resultZ0 = rTreeOfGeoSoil.query(RSZone.getEnvelopeInternal());
            if (!resultZ0.isEmpty()) {
                for (EnvelopeWithIndex<Integer> envel : resultZ0) {
                    //get the geo intersected
                    Geometry geoInter = RSZone.intersection(data.geoWithSoilType.get(envel.getId()).getGeo());
                    //add the intersected distance with ground effect
                    totRSDistance += getIntersectedDistance(geoInter) * this.data.geoWithSoilType.get(envel.getId()).getType();
                }
            }
            // Compute GPath using 2D Length
            gPath = totRSDistance / RSZone.getLength();

            List<TriIdWithIntersection> inters = new ArrayList<>();
            data.freeFieldFinder.computePropagationPath(srcCoord, receiverCoord, false, inters, true);
            List<Coordinate> rSground = data.freeFieldFinder.getGroundProfile(inters);
            altR = rSground.get(inters.size() - 1).z;    // altitude Receiver
            altS = rSground.get(0).z; // altitude Source
            double angle = new LineSegment(rSground.get(0), rSground.get(rSground.size() - 1)).angle();
            rSground = JTSUtility.getNewCoordinateSystem(rSground);

            // Compute mean ground plan
            double[] ab = JTSUtility.getLinearRegressionPolyline(removeDuplicates(rSground));
            Coordinate rotatedReceiver = new Coordinate(rSground.get(rSground.size() - 1));
            rotatedReceiver.setOrdinate(1, receiverCoord.z);
            Coordinate rotatedSource = new Coordinate(rSground.get(0));
            rotatedSource.setOrdinate(1, srcCoord.z);
            projReceiver = JTSUtility.makeProjectedPoint(ab[0], ab[1], rotatedReceiver);
            projSource = JTSUtility.makeProjectedPoint(ab[0], ab[1], rotatedSource);

            //projReceiver.x = rSground.get(rSground.size() - 1).x - projReceiver.x ;
            //projSource.x = rSground.get(rSground.size() - 1).x - projSource.x ;

            projReceiver = JTSUtility.getOldCoordinateSystem(projReceiver, angle);
            projSource = JTSUtility.getOldCoordinateSystem(projSource, angle);

            projReceiver.x = srcCoord.x + projReceiver.x;
            projSource.x = srcCoord.x + projSource.x;
            projReceiver.y = srcCoord.y + projReceiver.y;
            projSource.y = srcCoord.y + projSource.y;

            List<Coordinate> Test = new ArrayList<Coordinate>();
            Test.add(projSource);
            Test.add(projReceiver);

            JTSUtility.getLinearRegressionPolyline(removeDuplicates(JTSUtility.getNewCoordinateSystem(Test)));
            //double zr = rotatedReceiver.distance(JTSUtility.makeProjectedPoint(ab[0], ab[1], rotatedReceiver));
            //double zs = rotatedSource.distance(JTSUtility.makeProjectedPoint(ab[0], ab[1], rotatedSource));

            segments.add(new PropagationPath.SegmentPath(gPath, new Vector3D(projSource, projReceiver)));

        }

        points.add(new PropagationPath.PointPath(srcCoord, altS, gS, Double.NaN, -1, PropagationPath.PointPath.POINT_TYPE.SRCE));
        points.add(new PropagationPath.PointPath(receiverCoord, altR, gS, Double.NaN, -1, PropagationPath.PointPath.POINT_TYPE.RECV));

        PropagationDebugInfo propagationDebugInfo = null;
        if (debugInfo != null) {
            propagationDebugInfo = new PropagationDebugInfo(Arrays.asList(receiverCoord, srcCoord), new double[data.freq_lvl.size()]);
        }
        return new PropagationPath(true, points, segments, segments);

    }




    public PropagationPath computeHorizontalEdgeDiffraction(boolean obstructedSourceReceiver, Coordinate receiverCoord,
                                                            Coordinate srcCoord, boolean favorable,
                                                            List<PropagationDebugInfo> debugInfo) {

        GeometryFactory factory = new GeometryFactory();
        List<PropagationPath.PointPath> points = new ArrayList<PropagationPath.PointPath>();
        List<PropagationPath.SegmentPath> segments = new ArrayList<PropagationPath.SegmentPath>();
        List<PropagationPath.SegmentPath> srPath = new ArrayList<PropagationPath.SegmentPath>();
        boolean validDiffraction ;

        double epsilon = 1e-7;
        DiffractionWithSoilEffetZone diffDataWithSoilEffet;
        if (!obstructedSourceReceiver) {
            diffDataWithSoilEffet = data.freeFieldFinder.getPathInverse(receiverCoord, srcCoord);
            validDiffraction = false;
        } else {
            diffDataWithSoilEffet = data.freeFieldFinder.getPath(receiverCoord, srcCoord);
            validDiffraction = true;
        }

        if (validDiffraction) {
            Coordinate bufferedCoordinate1;
            Coordinate bufferedCoordinate2;
            for (int j=diffDataWithSoilEffet.getPath().size()-1; j > 1 ; j--){
                bufferedCoordinate1 = addBuffer(diffDataWithSoilEffet.getPath().get(j-1), srcCoord,true);
                bufferedCoordinate1.z += 0.1;
                bufferedCoordinate2 = addBuffer(diffDataWithSoilEffet.getPath().get(j), receiverCoord,true);
                bufferedCoordinate2.z += 0.1;

                PropagationPath propagationPath1 = computeFreefield(bufferedCoordinate1,bufferedCoordinate2,favorable, debugInfo);
                propagationPath1.getPointList().get(1).setType(PropagationPath.PointPath.POINT_TYPE.DIFH);
                if ( j == diffDataWithSoilEffet.getPath().size()-1){
                    propagationPath1.getPointList().get(0).setCoordinate(diffDataWithSoilEffet.getPath().get(j));
                    points.add(propagationPath1.getPointList().get(0));
                }
                points.add(propagationPath1.getPointList().get(1));
                segments.addAll(propagationPath1.getSegmentList());
            }
            bufferedCoordinate1 = addBuffer(diffDataWithSoilEffet.getPath().get(1), srcCoord,true);
            bufferedCoordinate1.z += 0.001;
            bufferedCoordinate2 = diffDataWithSoilEffet.getPath().get(0);

            PropagationPath propagationPath2 = computeFreefield(bufferedCoordinate2,bufferedCoordinate1 , favorable, debugInfo);
            points.add(propagationPath2.getPointList().get(1));
            segments.add(propagationPath2.getSegmentList().get(0));


        }
        else {
            PropagationPath propagationPath = computeFreefield(receiverCoord, srcCoord, true, debugInfo);
            points.addAll(propagationPath.getPointList());
            segments.addAll(propagationPath.getSegmentList());
            srPath.addAll(propagationPath.getSRList());
        }
        return new PropagationPath(true, points,segments,srPath);
    }


    public Coordinate addBuffer(Coordinate p1, Coordinate p2, boolean side){
        // Translate reflection point by epsilon value to
        // increase computation robustness

        Coordinate coordinate = new Coordinate();
        Coordinate vec_epsilon = new Coordinate(
                p1.x - p2.x,
                p1.y - p2.y,
                p1.z - p2.z);
        double length = vec_epsilon
                .distance(new Coordinate(0., 0., 0.));
        // Normalize vector
        vec_epsilon.x /= length;
        vec_epsilon.y /= length;
        vec_epsilon.z/= length;
        // Multiply by epsilon in meter
        vec_epsilon.x *= 0.0001;
        vec_epsilon.y *= 0.0001;
        vec_epsilon.z *= 0.0001;
        // Translate reflection pt by epsilon to get outside
        // the wall
        // if side = false, p1 goes to p2
        if (!side){
            coordinate.x = p1.x+vec_epsilon.x;
            coordinate.y = p1.y+vec_epsilon.y;
            coordinate.z = p1.z+vec_epsilon.z;
        }else{
            coordinate.x = p1.x-vec_epsilon.x;
            coordinate.y = p1.y-vec_epsilon.y;
            coordinate.z = p1.z+vec_epsilon.z;
        }
        return coordinate;
    }
    /**
     * Add vertical edge diffraction noise contribution to energetic sum.
     * @param regionCorners
     * @param srcCoord
     * @param receiverCoord

     */
    public List<ArrayList<Coordinate>> computeVerticalEdgeDiffraction2( List<Coordinate> regionCorners, Coordinate srcCoord,
                                               Coordinate receiverCoord, List<PropagationDebugInfo> debugInfo) {
        final LineSegment receiverSrc = new LineSegment(receiverCoord, srcCoord);
        final double SrcReceiverDistance = CGAlgorithms3D.distance(srcCoord, receiverCoord);

        List<ArrayList<Coordinate>> paths  = new ArrayList<>();

        // Get the first valid receiver->corner
        int freqcount = data.freq_lvl.size();
        List<Integer> curCorner = new ArrayList<>();

        int firstCorner = nextFreeFieldNode(regionCorners, receiverCoord, receiverSrc, curCorner, 0, data.freeFieldFinder);
        if (firstCorner != -1) {
            // History of propagation through corners
            curCorner.add(firstCorner);
            while (!curCorner.isEmpty()) {
                Coordinate lastCorner = getProjectedZCoordinate(regionCorners.get(curCorner.get(curCorner.size() - 1)
                ), receiverSrc);
                // Test Path is free to the source
                if (data.freeFieldFinder.isFreeField(lastCorner, srcCoord)) {
                    // True then the path is clear
                    // Compute attenuation level
                    double eLength = 0;
                    //Compute distance of the corner path
                    for (int ie = 1; ie < curCorner.size(); ie++) {
                        Coordinate cornerA = regionCorners.get(curCorner.get(ie));
                        Coordinate cornerB = regionCorners.get(curCorner.get(ie - 1));
                        eLength += CGAlgorithms3D.distance(cornerA, cornerB);
                    }
                    // delta=SO^1+O^nO^(n+1)+O^nnR
                    double receiverCornerDistance = CGAlgorithms3D.distance(receiverCoord,
                            regionCorners.get(curCorner.get(0)));
                    double sourceCornerDistance = CGAlgorithms3D.distance(srcCoord,
                            regionCorners.get(curCorner.get(curCorner.size() - 1)));
                    double diffractionFullDistance = receiverCornerDistance + eLength
                            //Corner to corner distance
                            + sourceCornerDistance;
                    if (diffractionFullDistance < data.maxSrcDist) {
                        diffractionPathCount++;
                        double delta = diffractionFullDistance - SrcReceiverDistance;
                        PropagationDebugInfo propagationDebugInfo = null;
                        if (debugInfo != null) {
                            ArrayList<Coordinate> path = new ArrayList<>(curCorner.size() + 2);
                            path.add(receiverCoord);
                            for (Integer aCurCorner : curCorner) {
                                Coordinate Corner = getProjectedZCoordinate(regionCorners.get(aCurCorner), receiverSrc);
                                path.add(Corner);
                            }
                            path.add(srcCoord);
                            propagationDebugInfo = new PropagationDebugInfo(path, new double[freqcount]);
                            paths.add(path);
                        }

                        if (debugInfo != null) {
                            debugInfo.add(propagationDebugInfo);
                        }
                    }
                }
                // Process to the next corner
                int nextCorner = -1;
                if (data.diffractionOrder > curCorner.size()) {
                    // Continue to next order valid corner
                    nextCorner = nextFreeFieldNode(regionCorners, lastCorner, receiverSrc, curCorner, 0,
                            data.freeFieldFinder);
                    if (nextCorner != -1) {
                        curCorner.add(nextCorner);
                    }
                }
                while (nextCorner == -1 && !curCorner.isEmpty()) {
                    Coordinate startPoint = receiverCoord;
                    if (curCorner.size() > 1) {
                        startPoint = regionCorners.get(curCorner.get(curCorner.size() - 2));
                    }
                    // Next free field corner
                    nextCorner = nextFreeFieldNode(regionCorners, startPoint, receiverSrc, curCorner,
                            curCorner.get(curCorner.size() - 1), data.freeFieldFinder);
                    if (nextCorner != -1) {
                        curCorner.set(curCorner.size() - 1, nextCorner);
                    } else {
                        curCorner.remove(curCorner.size() - 1);
                    }
                }
            }
        }
    return paths;
    }

    public HashSet<Integer> getBuildingsOnPath(Coordinate p1,
                              Coordinate p2,List<Wall> nearBuildingsWalls){
        HashSet<Integer> buildingsOnPath = new HashSet<>();
        Boolean somethingHideReceiver = true;
        Boolean buildingOnPath = true;
        List<TriIdWithIntersection> propagationPath = new ArrayList<>();
        if (!data.computeVerticalDiffraction || !data.freeFieldFinder.isHasBuildingWithHeight()) {
            somethingHideReceiver = !data.freeFieldFinder.isFreeField(p1, p2);
        } else {

            if (!data.freeFieldFinder.computePropagationPaths(p1, p2, nearBuildingsWalls, false, propagationPath, true)) {
                // Propagation path not found, there is not direct field
                somethingHideReceiver = true;
            } else {
                if (!propagationPath.isEmpty()) {
                    for (TriIdWithIntersection inter : propagationPath) {
                        if (inter.isIntersectionOnBuilding() || inter.isIntersectionOnTopography()) {
                            somethingHideReceiver = true;
                        }
                        if (inter.getBuildingId() != 0) {
                            buildingOnPath = true;
                            buildingsOnPath.add(inter.getBuildingId());

                        }
                    }
                }
            }

        }
        return buildingsOnPath;
    }

    public List<List<Coordinate>> computeVerticalEdgeDiffraction( Coordinate p1,
                                                                       Coordinate p2,List<Wall> nearBuildingsWalls, List<PropagationDebugInfo> debugInfo) {
        final LineSegment receiverSrc = new LineSegment(p1, p2);
        List<List<Coordinate>> paths  = new ArrayList<>();
        HashSet<Integer> buildingsOnPath = new HashSet<>();
        HashSet<Integer> buildingsOnPath2 = new HashSet<>();
        GeometryFactory geometryFactory = new GeometryFactory();

        List<Coordinate> coordinates = new ArrayList<>();
        int indexp1 = 0;
        int indexp2 = 0;

        boolean sthg = true;
        buildingsOnPath = getBuildingsOnPath(p1, p2, nearBuildingsWalls);
        while (sthg) {

            Geometry[] geometries = new Geometry[buildingsOnPath.size() + 2];
            int k = 0;
            for (int i : buildingsOnPath) {
                List<Coordinate> buildPolygon = data.freeFieldFinder.getWideAnglePointsByBuilding(i,Math.PI * (1 + 1 / 16.0), Math.PI * (2 - (1 / 16.)));
                geometries[k++] = geometryFactory.createPolygon((Coordinate[]) buildPolygon.toArray(new Coordinate[] {}));
            }

            geometries[k++] = geometryFactory.createPoint(p1);
            geometries[k++] = geometryFactory.createPoint(p2);
            Geometry convexhull = geometryFactory.createGeometryCollection(geometries).convexHull();

            ArrayList<Coordinate> path = new ArrayList<>();
            k = 0;
            for (Coordinate c : convexhull.getCoordinates()) {
                if (c.equals(p1)) {
                    indexp1 = k;
                }
                if (c.equals(p2)) {
                    indexp2 = k;
                }
                k++;
            }
            sthg = false;
            buildingsOnPath2.clear();
            coordinates = Arrays.asList(convexhull.getCoordinates());
            for (k = 0; k < coordinates.size(); k++) {
               coordinates.get(k).setCoordinate(getProjectedZCoordinate(coordinates.get(k), receiverSrc));
              if (k < coordinates.size() - 1) {
                    buildingsOnPath2 = getBuildingsOnPath(coordinates.get(k), coordinates.get(k + 1), nearBuildingsWalls);
                    if (!buildingsOnPath2.isEmpty()) {
                        buildingsOnPath.addAll(buildingsOnPath2);
                        sthg = true;
                        break;
                    }
                }
            }
        }

        if (indexp1 < indexp2){
            paths.add(coordinates.subList(indexp1, indexp2+1));
            ArrayList<Coordinate> inversePath = new ArrayList<>();
            inversePath.addAll(coordinates.subList(indexp2, coordinates.size()-1));
            inversePath.addAll(coordinates.subList(0, indexp1+1));
            paths.add(inversePath);
        }
        else
        {
            paths.add(coordinates.subList(indexp2, indexp1+1));
            ArrayList<Coordinate> inversePath = new ArrayList<>();
            inversePath.addAll(coordinates.subList(indexp1, coordinates.size()-1));
            inversePath.addAll(coordinates.subList(0, indexp2+1));
            paths.add(inversePath);
        }

        return paths;
    }

    /**
     * Compute project Z coordinate between p0 p1 of x,y.
     * @param coordinateWithoutZ coordinate to set the Z value from Z interpolation of line
     * @param line Extract Z values of this segment
     * @return coordinateWithoutZ with Z value computed from line.
     */
    private static Coordinate getProjectedZCoordinate(Coordinate coordinateWithoutZ, LineSegment line) {
        // Z value is the interpolation of source-receiver line
        return new Coordinate(coordinateWithoutZ.x, coordinateWithoutZ.y, Vertex.interpolateZ(
                line.closestPoint(coordinateWithoutZ), line.p0, line.p1));
    }

    /**
     * ISO-9613 p1
     * @param frequency acoustic frequency (Hz)
     * @param temperature Temperative in celsius
     * @param pressure atmospheric pressure (in Pa)
     * @param humidity relative humidity (in %) (0-100)
     * @return Attenuation coefficient dB/KM
     */
    public static double getAlpha(double frequency, double temperature, double pressure, double humidity) {
        return PropagationProcessData.getCoefAttAtmos(frequency, humidity, pressure, temperature + PropagationProcessData.K_0);
    }

    private int nextFreeFieldNode(List<Coordinate> nodes, Coordinate startPt, LineSegment segmentConstraint,
                                  List<Integer> NodeExceptions, int firstTestNode,
                                  FastObstructionTest freeFieldFinder) {
        int validNode = firstTestNode;
        while (NodeExceptions.contains(validNode)
                || (validNode < nodes.size() && (Math.abs(segmentConstraint.projectionFactor(nodes.get(validNode))) > 1 || !freeFieldFinder.isFreeField(
                startPt, getProjectedZCoordinate(nodes.get(validNode), segmentConstraint))))) {
            validNode++;
        }
        if (validNode >= nodes.size()) {
            return -1;
        }
        return validNode;
    }

    /**
     * Compute attenuation of sound energy by distance. Minimum distance is one
     * meter.
     *
     * @param Wj       Source level
     * @param distance Distance in meter
     * @return Attenuated sound level. Take only account of geometric dispersion
     * of sound wave.
     */
    public static double attDistW(double Wj, double distance) {
        return Wj / (4 * Math.PI * Math.max(1, distance * distance));
    }

    private boolean[]  findBuildingOnPath(Coordinate srcCoord,
                                         Coordinate receiverCoord, List<Wall> nearBuildingsWalls){

        boolean somethingHideReceiver = false;
        boolean buildingOnPath = false;
        boolean[] somethingOnPath = new boolean[2];
        if (!data.computeVerticalDiffraction || !data.freeFieldFinder.isHasBuildingWithHeight()) {
            somethingHideReceiver = !data.freeFieldFinder.isFreeField(receiverCoord, srcCoord);
        } else {
            List<TriIdWithIntersection> propagationPath = new ArrayList<>();
            if (!data.freeFieldFinder.computePropagationPaths(receiverCoord, srcCoord, nearBuildingsWalls, false, propagationPath, false)) {
                // Propagation path not found, there is not direct field
                somethingHideReceiver = true;
            } else {
                if (!propagationPath.isEmpty()) {
                    for (TriIdWithIntersection inter : propagationPath) {
                        if (inter.isIntersectionOnBuilding() || inter.isIntersectionOnTopography()) {
                            somethingHideReceiver = true;
                        }
                        if (inter.getBuildingId() != 0) {
                            buildingOnPath = true;
                        }
                    }
                }
            }
        }
        somethingOnPath[0] = somethingHideReceiver;
        somethingOnPath[1] = buildingOnPath;
        return somethingOnPath;
    }

    private List<PropagationPath> directPath(Coordinate srcCoord,
                            Coordinate receiverCoord, List<Wall> nearBuildingsWalls, boolean vertivalDiffraction, List<PropagationDebugInfo> debugInfo){


        List<PropagationPath> propagationPaths = new ArrayList<>();

        // Then, check if the source is visible from the receiver (not
        // hidden by a building)
        // Create the direct Line
        boolean buildingInArea = false;

        boolean[] somethingOnPath = findBuildingOnPath( srcCoord,  receiverCoord,  nearBuildingsWalls);
        boolean somethingHideReceiver = somethingOnPath[0];
        boolean buildingOnPath = somethingOnPath[1];

       // double fav_probability = favrose[(int) (Math.round(calcRotationAngleInDegrees(srcCoord, receiverCoord) / 30))];

        if (!somethingHideReceiver && !buildingOnPath) {
            PropagationPath propagationPath = computeFreefield(receiverCoord, srcCoord, true, debugInfo);
            propagationPaths.add(propagationPath);
            propagationPath.setFavorable(false);
            propagationPaths.add(propagationPath);
        }

        //Process diffraction 3D
        if (data.computeVerticalDiffraction && buildingOnPath) {
            PropagationPath propagationPath =  computeHorizontalEdgeDiffraction(somethingHideReceiver, receiverCoord, srcCoord, true, debugInfo);
            propagationPaths.add(propagationPath);
            propagationPath.setFavorable(false);
            propagationPaths.add(propagationPath);
        }

        if (somethingHideReceiver && data.diffractionOrder > 0 && vertivalDiffraction ) {

            PropagationPath propagationPath = new PropagationPath();
            PropagationPath propagationPath2 = new PropagationPath();

            // Left hand
            List<List<Coordinate>> diffractedPaths = computeVerticalEdgeDiffraction( srcCoord, receiverCoord,  nearBuildingsWalls,debugInfo);
            List<Coordinate> coordinates = diffractedPaths.get(0);
            Collections.reverse(coordinates);
            if (coordinates.size()>1) {
                Coordinate bufferedCoordinate;
                bufferedCoordinate = addBuffer(coordinates.get(1), coordinates.get(0),true);
                propagationPath = computeFreefield(bufferedCoordinate,coordinates.get(0), true, debugInfo);
                propagationPath.getPointList().get(1).setType(PropagationPath.PointPath.POINT_TYPE.DIFV);
                propagationPath2 = propagationPath;
                int j =0;
                for (j=1; j < coordinates.size()-2; j++){
                    bufferedCoordinate = addBuffer(coordinates.get(j+1), coordinates.get(j),true);
                    propagationPath = computeFreefield(bufferedCoordinate,coordinates.get(j), true, debugInfo);
                    propagationPath.getPointList().get(1).setType(PropagationPath.PointPath.POINT_TYPE.DIFV);
                    propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                    propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());
                }
                bufferedCoordinate = addBuffer(coordinates.get(j), coordinates.get(j+1),true);
                propagationPath = computeFreefield(coordinates.get(j+1),bufferedCoordinate, true, debugInfo);
                propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());
                propagationPaths.add(propagationPath2);
            }

            // Left hand
            coordinates = diffractedPaths.get(1);
            //Collections.reverse(coordinates);
            if (coordinates.size()>1) {
                Coordinate bufferedCoordinate;
                bufferedCoordinate = addBuffer(coordinates.get(1), coordinates.get(0),true);
                propagationPath = computeFreefield(bufferedCoordinate,coordinates.get(0), true, debugInfo);
                propagationPath.getPointList().get(1).setType(PropagationPath.PointPath.POINT_TYPE.DIFV);
                propagationPath2 = propagationPath;
                int j =0;

                for (j=1; j < coordinates.size()-2; j++){
                    bufferedCoordinate = addBuffer(coordinates.get(j+1), coordinates.get(j),true);
                    propagationPath = computeFreefield(bufferedCoordinate,coordinates.get(j), true, debugInfo);
                    propagationPath.getPointList().get(1).setType(PropagationPath.PointPath.POINT_TYPE.DIFV);
                    propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                    propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());
                }
                bufferedCoordinate = addBuffer(coordinates.get(j), coordinates.get(j+1),true);
                propagationPath = computeFreefield(coordinates.get(j+1),bufferedCoordinate, true, debugInfo);
                propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());
                propagationPaths.add(propagationPath2);
            }


        }



        return propagationPaths;
    }

    /**
     * Source-Receiver Direct+Reflection+Diffraction computation
     *
     * @param[in] srcCoord coordinate of source
     * @param[in] receiverCoord coordinate of receiver
     * @param[out] energeticSum Energy by frequency band
     * @param[in] alpha_atmo Atmospheric absorption by frequency band
     * @param[in] wj Source sound pressure level dB(A) by frequency band
     * @param[in] nearBuildingsWalls Walls within maxsrcdist
     * from receiver
     */
    @SuppressWarnings("unchecked")
    private void receiverSourcePropa(Coordinate srcCoord,
                                     Coordinate receiverCoord, double energeticSum[],
                                     double[] alpha_atmo, double[] favrose,
                                     List<Wall> nearBuildingsWalls, List<PropagationDebugInfo> debugInfo) {

        GeometryFactory factory = new GeometryFactory();
        List<PropagationPath> propagationPaths = new ArrayList<>();
        // Build mirrored receiver list from wall list

        double PropaDistance = srcCoord.distance(receiverCoord);
        if (PropaDistance < data.maxSrcDist) {
            propagationPaths = directPath(srcCoord,receiverCoord,nearBuildingsWalls,true, debugInfo);
            if (propagationPaths.size()>0) {
                dataOut.addPropagationPaths(propagationPaths);
            }

            // Process specular reflection
            if (data.reflexionOrder > 0) {
                PropagationPath propagationPath = computeReflexion(receiverCoord, srcCoord, true, nearBuildingsWalls, debugInfo);
                if (propagationPath.getPointList().size()>0) {
                    dataOut.addPropagationPath(propagationPath);
                    propagationPath.setFavorable(false);
                    dataOut.addPropagationPath(propagationPath);
                }
            } // End reflexion
            // ///////////


        }
    }

    private static void insertPtSource(Coordinate receiverPos, Coordinate ptpos, List<Double> wj, double li, List<Coordinate> srcPos, List<ArrayList<Double>> srcWj, PointsMerge sourcesMerger, List<Integer> srcSortedIndex, List<Double> srcDistSorted) {
        int mergedSrcIndex = sourcesMerger.getOrAppendVertex(ptpos);
        if (mergedSrcIndex < srcPos.size()) {
            ArrayList<Double> mergedWj = srcWj.get(mergedSrcIndex);
            //A source already exist and is close enough to merge
            for (int fb = 0; fb < wj.size(); fb++) {
                mergedWj.set(fb, mergedWj.get(fb) + wj.get(fb) * li);
            }
        } else {
            //New source
            ArrayList<Double> liWj = new ArrayList<Double>(wj.size());
            for (Double lvl : wj) {
                liWj.add(lvl * li);
            }
            srcPos.add(ptpos);
            srcWj.add(liWj);

            double dx = ptpos.x - receiverPos.x;
            double dy = ptpos.y - receiverPos.y;
            double dz = ptpos.z - receiverPos.z;

            double distanceSrcPt = Math.sqrt(dx * dx + dy * dy + dz * dz);// TODO i have change like line 384

            int index = Collections.binarySearch(srcDistSorted, distanceSrcPt);
            if (index >= 0) {
                srcSortedIndex.add(index, mergedSrcIndex);
                srcDistSorted.add(index, distanceSrcPt);
            } else {
                srcSortedIndex.add(-index - 1, mergedSrcIndex);
                srcDistSorted.add(-index - 1, distanceSrcPt);
            }
        }
    }

    /**
     * Compute the attenuation of atmospheric absorption
     *
     * @param Wj         Source energy
     * @param dist       Propagation distance
     * @param alpha_atmo Atmospheric alpha (dB/km)
     * @return
     */
    private Double attAtmW(double Wj, double dist, double alpha_atmo) {
        return dbaToW(wToDba(Wj) - (alpha_atmo * dist) / 1000.);
    }

    /**
     * Use {@link #cornersQuad} to fetch all region corners in max ref dist
     * @param fetchLine Compute distance from this line (Propagation line)
     * @param maxDistance Maximum distance to fetch corners (m)
     * @return Filtered corner index list
     */
    public List<Coordinate> fetchRegionCorners(LineSegment fetchLine, double maxDistance) {
        // Build Envelope
        GeometryFactory factory = new GeometryFactory();
        Geometry env = factory.createLineString(new Coordinate[]{fetchLine.p0, fetchLine.p1});
        env = env.buffer(maxDistance, 0, BufferParameters.CAP_FLAT);
        // Query corners in the current zone
        ArrayCoordinateListVisitor cornerQuery = new ArrayCoordinateListVisitor(env);
        Envelope queryEnv = new Envelope(fetchLine.p0, fetchLine.p1);
        queryEnv.expandBy(maxDistance);
        cornersQuad.query(queryEnv, cornerQuery);
        return cornerQuery.getItems();
    }

    /**
     * Compute sound level by frequency band at this receiver position
     *
     * @param receiverCoord
     * @param energeticSum
     */
    public void computeSoundLevelAtPosition(Coordinate receiverCoord, double energeticSum[], List<PropagationDebugInfo> debugInfo) {
        // List of walls within maxReceiverSource distance
        double srcEnergeticSum = BASE_LVL; //Global energetic sum of all sources processed
        STRtree walls = new STRtree();
        if (data.reflexionOrder > 0) {
            for (Wall wall : data.freeFieldFinder.getLimitsInRange(
                    data.maxSrcDist, receiverCoord, false)) {
                walls.insert(new Envelope(wall.p0, wall.p1), wall);
            }
        }
        // Source search by multiple range query
        HashSet<Integer> processedLineSources = new HashSet<Integer>(); //Already processed Raw source (line and/or points)
        double[] ranges = new double[]{Math.min(FIRST_STEP_RANGE, data.maxSrcDist / 6), data.maxSrcDist / 5, data.maxSrcDist / 4, data.maxSrcDist / 2, data.maxSrcDist};
        long sourceCount = 0;

        for (double searchSourceDistance : ranges) {
            Envelope receiverSourceRegion = new Envelope(receiverCoord.x
                    - searchSourceDistance, receiverCoord.x + searchSourceDistance,
                    receiverCoord.y - searchSourceDistance, receiverCoord.y
                    + searchSourceDistance
            );
            Iterator<Integer> regionSourcesLst = data.sourcesIndex
                    .query(receiverSourceRegion);

            PointsMerge sourcesMerger = new PointsMerge(MERGE_SRC_DIST);
            List<Integer> srcSortByDist = new ArrayList<Integer>();
            List<Double> srcDist = new ArrayList<Double>();
            List<Coordinate> srcPos = new ArrayList<Coordinate>();
            List<ArrayList<Double>> srcWj = new ArrayList<ArrayList<Double>>();
            while (regionSourcesLst.hasNext()) {
                Integer srcIndex = regionSourcesLst.next();
                if (!processedLineSources.contains(srcIndex)) {
                    processedLineSources.add(srcIndex);
                    Geometry source = data.sourceGeometries.get(srcIndex);
                    List<Double> wj = data.wj_sources.get(srcIndex); // DbaToW(sdsSources.getDouble(srcIndex,dbField
                    if (source instanceof Point) {
                        Coordinate ptpos = source.getCoordinate();
                        insertPtSource(receiverCoord, ptpos, wj, 1., srcPos, srcWj, sourcesMerger, srcSortByDist, srcDist);
                        // Compute li to equation 4.1 NMPB 2008 (June 2009)
                    } else {
                        // Discretization of line into multiple point
                        // First point is the closest point of the LineString from
                        // the receiver
                        ArrayList<Coordinate> pts = new ArrayList<Coordinate>();
                        double li = splitLineStringIntoPoints(source, receiverCoord,
                                pts, data.minRecDist);
                        for (Coordinate pt : pts) {
                            insertPtSource(receiverCoord, pt, wj, li, srcPos, srcWj, sourcesMerger, srcSortByDist, srcDist);
                        }
                        // Compute li to equation 4.1 NMPB 2008 (June 2009)
                    }
                }
            }
            //Iterate over source point sorted by their distance from the receiver
            for (int mergedSrcId : srcSortByDist) {
                // For each Pt Source - Pt Receiver
                Coordinate srcCoord = srcPos.get(mergedSrcId);
                ArrayList<Double> wj = srcWj.get(mergedSrcId);
                double allreceiverfreqlvl = GetGlobalLevel(nbfreq, energeticSum);
                double allsourcefreqlvl = 0;
                for (int idfreq = 0; idfreq < nbfreq; idfreq++) {
                    allsourcefreqlvl += wj.get(idfreq);
                }
                double wAttDistSource = attDistW(allsourcefreqlvl, CGAlgorithms3D.distance(srcCoord, receiverCoord));
                srcEnergeticSum += wAttDistSource;
                if (Math.abs(wToDba(wAttDistSource + allreceiverfreqlvl) - wToDba(allreceiverfreqlvl)) > data.forgetSource) {
                    sourceCount++;
                    Envelope query = new Envelope(receiverCoord, srcCoord);
                    query.expandBy(Math.min(data.maxRefDist, srcCoord.distance(receiverCoord)));
                    List queryResult = walls.query(query);
                    receiverSourcePropa(srcCoord, receiverCoord, energeticSum,
                            alpha_atmo, data.windRose,
                            (List<Wall>) queryResult, debugInfo);
                }
            }
            //srcEnergeticSum=GetGlobalLevel(nbfreq,energeticSum);
            if (Math.abs(wToDba(attDistW(W_RANGE, searchSourceDistance) + srcEnergeticSum) - wToDba(srcEnergeticSum)) < data.forgetSource) {
                break; //Stop search for furthest sources
            }
        }
        dataOut.appendSourceCount(sourceCount);
    }

    /**
     * Compute sound level by frequency band at this receiver position
     *
     * @param receiverCoord
     * @param energeticSum
     */
    public void computeRaysAtPosition(Coordinate receiverCoord, double energeticSum[], List<PropagationDebugInfo> debugInfo) {
        // List of walls within maxReceiverSource distance
        double srcEnergeticSum = BASE_LVL; //Global energetic sum of all sources processed
        STRtree walls = new STRtree();
        if (data.reflexionOrder > 0) {
            for (Wall wall : data.freeFieldFinder.getLimitsInRange(
                    data.maxSrcDist, receiverCoord, false)) {
                walls.insert(new Envelope(wall.p0, wall.p1), wall);
            }
        }

        // Source search by multiple range query
        HashSet<Integer> processedLineSources = new HashSet<Integer>(); //Already processed Raw source (line and/or points)
        double[] ranges = new double[]{Math.min(FIRST_STEP_RANGE, data.maxSrcDist / 6), data.maxSrcDist / 5, data.maxSrcDist / 4, data.maxSrcDist / 2, data.maxSrcDist};
        long sourceCount = 0;

        for (double searchSourceDistance : ranges) {
            Envelope receiverSourceRegion = new Envelope(receiverCoord.x
                    - searchSourceDistance, receiverCoord.x + searchSourceDistance,
                    receiverCoord.y - searchSourceDistance, receiverCoord.y
                    + searchSourceDistance
            );
            Iterator<Integer> regionSourcesLst = data.sourcesIndex
                    .query(receiverSourceRegion);

            PointsMerge sourcesMerger = new PointsMerge(MERGE_SRC_DIST);
            List<Integer> srcSortByDist = new ArrayList<Integer>();
            List<Double> srcDist = new ArrayList<Double>();
            List<Coordinate> srcPos = new ArrayList<Coordinate>();
            List<ArrayList<Double>> srcWj = new ArrayList<ArrayList<Double>>();
            while (regionSourcesLst.hasNext()) {
                Integer srcIndex = regionSourcesLst.next();
                if (!processedLineSources.contains(srcIndex)) {
                    processedLineSources.add(srcIndex);
                    Geometry source = data.sourceGeometries.get(srcIndex);
                    List<Double> wj = data.wj_sources.get(srcIndex); // DbaToW(sdsSources.getDouble(srcIndex,dbField
                    if (source instanceof Point) {
                        Coordinate ptpos = source.getCoordinate();
                        insertPtSource(receiverCoord, ptpos, wj, 1., srcPos, srcWj, sourcesMerger, srcSortByDist, srcDist);
                        // Compute li to equation 4.1 NMPB 2008 (June 2009)
                    } else {
                        // Discretization of line into multiple point
                        // First point is the closest point of the LineString from
                        // the receiver
                        ArrayList<Coordinate> pts = new ArrayList<Coordinate>();
                        double li = splitLineStringIntoPoints(source, receiverCoord,
                                pts, data.minRecDist);
                        for (Coordinate pt : pts) {
                            insertPtSource(receiverCoord, pt, wj, li, srcPos, srcWj, sourcesMerger, srcSortByDist, srcDist);
                        }
                        // Compute li to equation 4.1 NMPB 2008 (June 2009)
                    }
                }
            }

            //Iterate over source point sorted by their distance from the receiver
            for (int mergedSrcId : srcSortByDist) {
                // For each Pt Source - Pt Receiver
                Coordinate srcCoord = srcPos.get(mergedSrcId);
                ArrayList<Double> wj = srcWj.get(mergedSrcId);
                double allreceiverfreqlvl = GetGlobalLevel(nbfreq, energeticSum);
                double allsourcefreqlvl = 0;
                for (int idfreq = 0; idfreq < nbfreq; idfreq++) {
                    allsourcefreqlvl += wj.get(idfreq);
                }
                double wAttDistSource = attDistW(allsourcefreqlvl, CGAlgorithms3D.distance(srcCoord, receiverCoord));
                srcEnergeticSum += wAttDistSource;
                if (Math.abs(wToDba(wAttDistSource + allreceiverfreqlvl) - wToDba(allreceiverfreqlvl)) > data.forgetSource) {
                    sourceCount++;
                    Envelope query = new Envelope(receiverCoord, srcCoord);
                    query.expandBy(Math.min(data.maxRefDist, srcCoord.distance(receiverCoord)));
                    List queryResult = walls.query(query);
                    receiverSourcePropa(srcCoord, receiverCoord, energeticSum,
                            alpha_atmo, data.windRose,
                            (List<Wall>) queryResult, debugInfo);
                }
            }
            //srcEnergeticSum=GetGlobalLevel(nbfreq,energeticSum);
            if (Math.abs(wToDba(attDistW(W_RANGE, searchSourceDistance) + srcEnergeticSum) - wToDba(srcEnergeticSum)) < data.forgetSource) {
                break; //Stop search for furthest sources
            }
        }
        dataOut.appendSourceCount(sourceCount);
    }

    /**
     * Must be called before computeSoundLevelAtPosition
     */
    public void initStructures() {
        nbfreq = data.freq_lvl.size();
        // Init wave length for each frequency
        freq_lambda = new double[nbfreq];
        for (int idf = 0; idf < nbfreq; idf++) {
            if (data.freq_lvl.get(idf) > 0) {
                freq_lambda[idf] = data.celerity / data.freq_lvl.get(idf);
            } else {
                freq_lambda[idf] = 1;
            }
        }
        // Compute atmospheric alpha value by specified frequency band
        alpha_atmo = new double[data.freq_lvl.size()];
        for (int idfreq = 0; idfreq < nbfreq; idfreq++) {
            alpha_atmo[idfreq] = getAlpha(data.freq_lvl.get(idfreq), data.temperature, data.pressure, data.humidity);
        }
        // /////////////////////////////////////////////
        // Search diffraction corners
        cornersQuad = new Quadtree();
        if (data.diffractionOrder > 0) {
            List<Coordinate> corners = data.freeFieldFinder.getWideAnglePoints(
                    Math.PI * (1 + 1 / 16.0), Math.PI * (2 - (1 / 16.)));
            // Build Quadtree
            for (Coordinate corner : corners) {
                cornersQuad.insert(new Envelope(corner), corner);
            }
        }
        //Build R-tree for soil geometry and soil type
        rTreeOfGeoSoil = new STRtree();
        if (data.geoWithSoilType != null) {
            for (int i = 0; i < data.geoWithSoilType.size(); i++) {
                GeoWithSoilType geoWithSoilType = data.geoWithSoilType.get(i);
                rTreeOfGeoSoil.insert(geoWithSoilType.getGeo().getEnvelopeInternal(),
                        new EnvelopeWithIndex<Integer>(geoWithSoilType.getGeo().getEnvelopeInternal(), i));
            }
        }
    }

    public void runDebug(List<PropagationDebugInfo> debugInfo) {
        try {
            initStructures();

            // Computed sound level of vertices
            dataOut.setVerticesSoundLevel(new double[data.receivers.size()]);

            // For each vertices, find sources where the distance is within
            // maxSrcDist meters
            ProgressVisitor propaProcessProgression = data.cellProg;

            // todo check if db_d as values inside table if false then send error
            Runtime runtime = Runtime.getRuntime();
            int splitCount = runtime.availableProcessors();
            ThreadPool threadManager = new ThreadPool(
                    splitCount,
                    splitCount + 1, Long.MAX_VALUE,
                    TimeUnit.SECONDS);
            int maximumReceiverBatch = (int) Math.ceil(data.receivers.size() / (double) splitCount);
            int endReceiverRange = 0;
            while (endReceiverRange < data.receivers.size()) {
                int newEndReceiver = Math.min(endReceiverRange + maximumReceiverBatch, data.receivers.size());
                RangeReceiversComputation batchThread = new RangeReceiversComputation(endReceiverRange,
                        newEndReceiver, this, propaProcessProgression, debugInfo);
                //batchThread.run();
                threadManager.executeBlocking(batchThread);
                endReceiverRange = newEndReceiver;
            }
            threadManager.shutdown();
            threadManager.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            dataOut.appendFreeFieldTestCount(data.freeFieldFinder.getNbObstructionTest());
            dataOut.appendCellComputed();
            dataOut.appendDiffractionPath(diffractionPathCount);
            dataOut.appendReflexionPath(refpathcount);
        } catch (Exception ex) {
            LOGGER.error(ex.getLocalizedMessage(), ex);
        }
    }

    @Override
    public void run() {
        runDebug(null);
    }


    private double getIntersectedDistance(Geometry geo) {

        double totDistance = 0.;
        for (int i = 0; i < geo.getNumGeometries(); i++) {
            Coordinate[] coordinates = geo.getGeometryN(i).getCoordinates();
            if (coordinates.length > 1 && geo.getGeometryN(i) instanceof LineString) {
                totDistance += geo.getGeometryN(i).getLength();
            }
        }
        return totDistance;

    }

    /**
     * getASoil use equation ASol in NF S 31-133 page 41 to calculate Attenuation(or contribution) Ground Effect
     *
     * @param zs       z of source point
     * @param zr       z of receiver point
     * @param dp       dp in equation
     * @param gw       Gw
     * @param fm       frequency
     * @param AGroundHmin min ASoil
     * @param cel      sound celerity m/s
     * @return ASoil
     */
    private static double getASoil(double zs, double zr, double dp, double gw, int fm, double AGroundHmin, double cel) {
        //NF S 31-133 page 41 c
        double k = 2 * Math.PI * fm / cel;
        //NF S 31-113 page 41 w
        double w = 0.0185 * Math.pow(fm, 2.5) * Math.pow(gw, 2.6) /
                (Math.pow(fm, 1.5) * Math.pow(gw, 2.6) + 1.3 * Math.pow(10, 3) * Math.pow(fm, 0.75) * Math.pow(gw, 1.3) + 1.16 * Math.pow(10, 6));
        //NF S 31-113 page 41 Cf
        double cf = dp * (1 + 3 * w * dp * Math.pow(Math.E, -Math.sqrt(w * dp))) / (1 + w * dp);
        //NF S 31-113 page 41 A sol
        double ASoil = -10 * Math.log10(4 * Math.pow(k, 2) / Math.pow(dp, 2) *
                (Math.pow(zs, 2) - Math.sqrt(2 * cf / k) * zs + cf / k) * (Math.pow(zr, 2) - Math.sqrt(2 * cf / k) * zr + cf / k));
        ASoil = Math.max(ASoil, AGroundHmin);
        return ASoil;

    }

    /**
     * getAGroundF use equation ASol in NF S 31-133 page 41 to calculate Attenuation(or contribution) Ground Effect
     *
     * @param zs          z of source point
     * @param zr          z of receiver point
     * @param dp          dp in equation
     * @param gw          Gw
     * @param fm          frequency
     * @param AGroundFMin min ASoil
     * @param cel         Sound celerity m/s
     * @return AGroundF
     */
    private static double getAGroundF(double zs, double zr, double dp, double gw, int fm, double AGroundFMin, double cel) {
        // CNOSSOS p89


        //NF S 31-133 page 41 c
        double k = 2 * Math.PI * fm / cel;
        //NF S 31-113 page 41 w
        double w = 0.0185 * Math.pow(fm, 2.5) * Math.pow(gw, 2.6) /
                (Math.pow(fm, 1.5) * Math.pow(gw, 2.6) + 1.3 * Math.pow(10, 3) * Math.pow(fm, 0.75) * Math.pow(gw, 1.3) + 1.16 * Math.pow(10, 6));
        //NF S 31-113 page 41 Cf
        double cf = dp * (1 + 3 * w * dp * Math.pow(Math.E, -Math.sqrt(w * dp))) / (1 + w * dp);
        //NF S 31-113 page 41 A sol
        double AGroundF = -10 * Math.log10(4 * Math.pow(k, 2) / Math.pow(dp, 2) *
                (Math.pow(zs, 2) - Math.sqrt(2 * cf / k) * zs + cf / k) * (Math.pow(zr, 2) - Math.sqrt(2 * cf / k) * zr + cf / k));
        AGroundF = Math.max(AGroundF, AGroundFMin);
        return AGroundF;

    }


    /**
     * Formulae 7.18 and 7.20
     *
     * @param aSoil        Asol(O,R) or Asol(S,O) (sol mean ground)
     * @param deltaDifPrim Δdif(S,R') if Asol(S,O) is given or Δdif(S', R) if Asol(O,R)
     * @param deltaDif     Δdif(S, R)
     * @return Δsol(S, O) if Asol(S,O) is given or Δsol(O,R) if Asol(O,R) is given
     */
    private double getDeltaSoil(double aSoil, double deltaDifPrim, double deltaDif) {
        return -20 * Math.log10(1 + (Math.pow(10, -aSoil / 20) - 1) * Math.pow(10, -(deltaDifPrim - deltaDif) / 20));
    }

    private static class RangeReceiversComputation implements Runnable {
        private final int startReceiver; // Included
        private final int endReceiver; // Excluded
        private ComputeRays propagationProcess;
        private List<PropagationDebugInfo> debugInfo;
        private ProgressVisitor progressVisitor;

        private RangeReceiversComputation(int startReceiver, int endReceiver, ComputeRays propagationProcess, ProgressVisitor progressVisitor, List<PropagationDebugInfo> debugInfo) {
            this.startReceiver = startReceiver;
            this.endReceiver = endReceiver;
            this.propagationProcess = propagationProcess;
            this.debugInfo = debugInfo;
            this.progressVisitor = progressVisitor;
        }

        @Override
        public void run() {
            for (int idReceiver = startReceiver; idReceiver < endReceiver; idReceiver++) {
                Coordinate receiverCoord = propagationProcess.data.receivers.get(idReceiver);
                double energeticSum[] = new double[propagationProcess.data.freq_lvl.size()];
                Arrays.fill(energeticSum, 0d);

                propagationProcess.computeRaysAtPosition(receiverCoord, energeticSum, debugInfo);

                progressVisitor.endStep();
            }
        }
    }

    /**
     * Offset de Z coordinates by the height of the ground
     */
    private static final class AbsoluteCoordinateSequenceFilter implements CoordinateSequenceFilter {
        AtomicBoolean geometryChanged = new AtomicBoolean(false);
        FastObstructionTest fastObstructionTest;

        public AbsoluteCoordinateSequenceFilter(FastObstructionTest fastObstructionTest) {
            this.fastObstructionTest = fastObstructionTest;
        }

        @Override
        public void filter(CoordinateSequence coordinateSequence, int i) {
            Coordinate pt = coordinateSequence.getCoordinate(i);
            Double zGround = fastObstructionTest.getHeightAtPosition(pt);
            if (!zGround.isNaN()) {
                pt.setOrdinate(2, zGround + pt.getOrdinate(2));
                geometryChanged.set(true);
            }
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean isGeometryChanged() {
            return geometryChanged.get();
        }
    }
}
