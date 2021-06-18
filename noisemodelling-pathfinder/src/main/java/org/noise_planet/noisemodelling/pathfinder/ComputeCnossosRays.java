/*
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
package org.noise_planet.noisemodelling.pathfinder;

import org.apache.commons.math3.geometry.euclidean.threed.Line;
import org.apache.commons.math3.geometry.euclidean.threed.Plane;
import org.h2gis.api.ProgressVisitor;
import org.locationtech.jts.algorithm.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.noise_planet.noisemodelling.pathfinder.ComputeRays.splitLineStringIntoPoints;
import static org.noise_planet.noisemodelling.pathfinder.ComputeRays.sumArray;
import static org.noise_planet.noisemodelling.pathfinder.Utils.dbaToW;
import static org.noise_planet.noisemodelling.pathfinder.utils.AcousticPropagation.getADiv;

/**
 * @author Nicolas Fortin
 * @author Pierre Aumond
 */
public class ComputeCnossosRays {
    private final static double MAX_RATIO_HULL_DIRECT_PATH = 4;
    private final static Logger LOGGER = LoggerFactory.getLogger(ComputeCnossosRays.class);

    private int threadCount;

    public ComputeCnossosRays() {
        this.threadCount = Runtime.getRuntime().availableProcessors();
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    //TODO maybe keepRays can be moved inside CnossosPropagationData
    public void run(CnossosPropagationData data, IComputeRaysOut computeRaysOut) {
        ProgressVisitor progressVisitor = data.cellProg;
        int splitCount = threadCount;
        ThreadPool threadManager = new ThreadPool(splitCount, splitCount + 1, Long.MAX_VALUE, TimeUnit.SECONDS);
        int maximumReceiverBatch = (int) Math.ceil(data.receivers.size() / (double) splitCount);
        int endReceiverRange = 0;
        while (endReceiverRange < data.receivers.size()) {
            if (progressVisitor != null && progressVisitor.isCanceled()) {
                break;
            }
            int newEndReceiver = Math.min(endReceiverRange + maximumReceiverBatch, data.receivers.size());
            RangeReceiversComputation batchThread = new RangeReceiversComputation(endReceiverRange,
                    newEndReceiver, this, progressVisitor,
                    computeRaysOut.subProcess(endReceiverRange, newEndReceiver), data);
            if (threadCount != 1) {
                threadManager.executeBlocking(batchThread);
            } else {
                batchThread.run();
            }
            endReceiverRange = newEndReceiver;
        }
        threadManager.shutdown();
        try {
            threadManager.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            LOGGER.error(ex.getLocalizedMessage(), ex);
        }
    }

    public PropagationPath computeFreefield(ProfileBuilder.CutProfile cutProfile, double gs) {
        List<PointPath> points = new ArrayList<>();
        List<SegmentPath> segments = new ArrayList<>();

        segments.add(new SegmentPath(cutProfile.getGS(),
                new Vector3D(cutProfile.getSource().getCoordinate(), cutProfile.getReceiver().getCoordinate()),
                cutProfile.getSource().getCoordinate()));

        points.add(new PointPath(cutProfile.getSource(), PointPath.POINT_TYPE.SRCE, gs));
        points.add(new PointPath(cutProfile.getReceiver(), PointPath.POINT_TYPE.RECV, gs));

        return new PropagationPath(false, points, segments, segments);
    }

    public List<PropagationPath> directPath(Coordinate srcCoord, int srcId,
                                            Coordinate rcvCoord, int rcvId,
                                            CnossosPropagationData data) {
        List<PropagationPath> propagationPaths = new ArrayList<>();
        ProfileBuilder.CutProfile cutProfile = data.profileBuilder.getProfile(srcCoord, rcvCoord, data.gS);
        boolean freeField = cutProfile.isFreeField();

        if(freeField) {
            propagationPaths.add(computeFreefield(cutProfile, data.gS));
        }
        else {
            if (data.isComputeVerticalDiffraction()) {
                PropagationPath freePath = computeFreefield(cutProfile, data.gS);
                PropagationPath propagationPath = computeHorizontalEdgeDiffraction(cutProfile, data.gS);
                propagationPath.getSRSegmentList().addAll(freePath.getSRSegmentList());
                propagationPaths.add(propagationPath);
            }
            if (data.isComputeHorizontalDiffraction()) {
                // todo if one of the points > roof or < floor, get out this path
                PropagationPath freePath = computeFreefield(cutProfile, data.gS);

                PropagationPath propagationPath = computeVerticalEdgeDiffraction(srcCoord, rcvCoord, data, "left");
                if (propagationPath.getPointList() != null) {
                    for (int i = 0; i < propagationPath.getSegmentList().size(); i++) {
                        if (propagationPath.getSegmentList().get(i).getSegmentLength() < 0.1) {
                            propagationPath.getSegmentList().remove(i);
                            propagationPath.getPointList().remove(i + 1);
                        }
                    }
                    propagationPath.setSRList(freePath.getSRSegmentList());
                    Collections.reverse(propagationPath.getPointList());
                    Collections.reverse(propagationPath.getSegmentList());
                    propagationPaths.add(propagationPath);
                }
                propagationPath = computeVerticalEdgeDiffraction(srcCoord, rcvCoord, data, "right");
                if (propagationPath.getPointList() != null) {
                    for (int i = 0; i < propagationPath.getSegmentList().size(); i++) {
                        if (propagationPath.getSegmentList().get(i).getSegmentLength() < 0.1) {
                            propagationPath.getSegmentList().remove(i);
                            propagationPath.getPointList().remove(i + 1);
                        }
                    }
                    propagationPath.setSRList(freePath.getSRSegmentList());
                    propagationPaths.add(propagationPath);
                }
            }
        }

        for(PropagationPath propagationPath : propagationPaths) {
            propagationPath.idSource = srcId;
            propagationPath.idReceiver = rcvId;
        }

        return propagationPaths;
    }

    public PropagationPath computeVerticalEdgeDiffraction(Coordinate receiverCoord,
                                                          Coordinate srcCoord, CnossosPropagationData data, String side) {

        PropagationPath propagationPath;
        PropagationPath propagationPath2 = new PropagationPath();
        List<Coordinate> coordinates = new ArrayList<>();

        if (side.equals("right")) {
            // Right hand
            coordinates = computeSideHull(false, srcCoord, receiverCoord, data.profileBuilder);
            Collections.reverse(coordinates);
        }
        if (side.equals("left")) {
            coordinates = computeSideHull(true, srcCoord, receiverCoord, data.profileBuilder);
            Collections.reverse(coordinates);
        }

        if (!coordinates.isEmpty()) {
            if (coordinates.size() > 2) {
                propagationPath = computeFreefield(data.profileBuilder.getProfile(coordinates.get(0), coordinates.get(1), data.gS), data.gS);
                propagationPath.getPointList().get(1).setType(PointPath.POINT_TYPE.DIFV);
                propagationPath2.setPointList(propagationPath.getPointList());
                propagationPath2.setSegmentList(propagationPath.getSegmentList());
                int j;
                for (j = 1; j < coordinates.size() - 2; j++) {
                    propagationPath = computeFreefield(data.profileBuilder.getProfile(coordinates.get(j), coordinates.get(j+1), data.gS), data.gS);
                    propagationPath.getPointList().get(1).setType(PointPath.POINT_TYPE.DIFV);
                    propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                    propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());
                }
                propagationPath = computeFreefield(data.profileBuilder.getProfile(coordinates.get(j), coordinates.get(j+1), data.gS), data.gS);
                propagationPath2.getPointList().add(propagationPath.getPointList().get(1));
                propagationPath2.getSegmentList().addAll(propagationPath.getSegmentList());

            }
        }
        return propagationPath2;
    }

    public List<Coordinate> computeSideHull(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder) {
        if (p1.equals(p2)) {
            return new ArrayList<>();
        }

        Envelope env = profileBuilder.getMeshEnvelope();
        env.expandToInclude(p1);
        env.expandToInclude(p2);
        env.expandBy(1);
        // Intersection test cache
        Set<LineSegment> freeFieldSegments = new HashSet<>();
        GeometryFactory geometryFactory = new GeometryFactory();

        List<Coordinate> input = new ArrayList<>();

        Coordinate[] coordinates = new Coordinate[0];
        int indexp1 = 0;
        int indexp2 = 0;

        boolean convexHullIntersects = true;

        input.add(p1);
        input.add(p2);

        Set<Integer> buildingInHull = new HashSet<>();

        Plane cutPlane = computeZeroRadPlane(p1, p2);

        IntersectionRayVisitor intersectionRayVisitor = new IntersectionRayVisitor(
                profileBuilder.getBuildings(), p1, p2, profileBuilder, input, buildingInHull, cutPlane);

        profileBuilder.getBuildingsOnPath(p1, p2, intersectionRayVisitor);

        int k;
        while (convexHullIntersects) {
            ConvexHull convexHull = new ConvexHull(input.toArray(new Coordinate[0]), geometryFactory);
            Geometry convexhull = convexHull.getConvexHull();

            if (convexhull.getLength() / p1.distance(p2) > MAX_RATIO_HULL_DIRECT_PATH) {
                return new ArrayList<>();
            }

            convexHullIntersects = false;
            coordinates = convexhull.getCoordinates();

            input.clear();
            input.addAll(Arrays.asList(coordinates));

            indexp1 = -1;
            for (int i = 0; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p1)) {
                    indexp1 = i;
                    break;
                }
            }
            if (indexp1 == -1) {
                // P1 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            // Transform array to set p1 at index=0
            Coordinate[] coordinatesShifted = new Coordinate[coordinates.length];
            // Copy from P1 to end in beginning of new array
            int len = (coordinates.length - 1) - indexp1;
            System.arraycopy(coordinates, indexp1, coordinatesShifted, 0, len);
            // Copy from 0 to P1 in the end of array
            System.arraycopy(coordinates, 0, coordinatesShifted, len, coordinates.length - len - 1);
            coordinatesShifted[coordinatesShifted.length - 1] = coordinatesShifted[0];
            coordinates = coordinatesShifted;
            indexp1 = 0;
            indexp2 = -1;
            for (int i = 1; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p2)) {
                    indexp2 = i;
                    break;
                }
            }
            if (indexp2 == -1) {
                // P2 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            for (k = 0; k < coordinates.length - 1; k++) {
                LineSegment freeFieldTestSegment = new LineSegment(coordinates[k], coordinates[k + 1]);
                // Ignore intersection if iterating over other side (not parts of what is returned)
                if (left && k < indexp2 || !left && k >= indexp2) {
                    if (!freeFieldSegments.contains(freeFieldTestSegment)) {
                        // Check if we still are in the propagation domain
                        if (!env.contains(coordinates[k]) ||
                                !env.contains(coordinates[k + 1])) {
                            // This side goes over propagation path
                            return new ArrayList<>();
                        }
                        intersectionRayVisitor = new IntersectionRayVisitor(profileBuilder.getBuildings(),
                                coordinates[k], coordinates[k + 1], profileBuilder, input, buildingInHull, cutPlane);
                        profileBuilder.getBuildingsOnPath(coordinates[k], coordinates[k + 1], intersectionRayVisitor);
                        if (!intersectionRayVisitor.doContinue()) {
                            convexHullIntersects = true;
                        }
                        if (!convexHullIntersects) {
                            freeFieldSegments.add(freeFieldTestSegment);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        // Check for invalid coordinates
        for (Coordinate p : coordinates) {
            if (p.z < 0) {
                return new ArrayList<>();
            }
        }

        if (left) {
            return Arrays.asList(Arrays.copyOfRange(coordinates, indexp1, indexp2 + 1));
        } else {
            ArrayList<Coordinate> inversePath = new ArrayList<>();
            inversePath.addAll(Arrays.asList(Arrays.copyOfRange(coordinates, indexp2, coordinates.length)));
            Collections.reverse(inversePath);
            return inversePath;
        }
    }

    public static Plane computeZeroRadPlane(Coordinate p0, Coordinate p1) {
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D s = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p0.x, p0.y, p0.z);
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D r = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p1.x, p1.y, p1.z);
        double angle = Math.atan2(p1.y - p0.y, p1.x - p0.x);
        // Compute rPrime, the third point of the plane that is at -PI/2 with SR vector
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D rPrime = s.add(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(Math.cos(angle - Math.PI / 2), Math.sin(angle - Math.PI / 2), 0));
        Plane p = new Plane(r, s, rPrime, 1e-6);
        // Normal of the cut plane should be upward
        if (p.getNormal().getZ() < 0) {
            p.revertSelf();
        }
        return p;
    }


    private static final class IntersectionRayVisitor implements ItemVisitor {
        Set<Integer> buildingsprocessed = new HashSet<>();
        List<ProfileBuilder.Building> buildings;
        Coordinate p1;
        Coordinate p2;
        LineString seg;
        Set<Integer> buildingsInIntersection;
        ProfileBuilder profileBuilder;
        Plane cutPlane;
        List<Coordinate> input;
        boolean foundIntersection = false;

        public IntersectionRayVisitor(List<ProfileBuilder.Building> buildings, Coordinate p1,
                                      Coordinate p2, ProfileBuilder profileBuilder, List<Coordinate> input, Set<Integer> buildingsInIntersection, Plane cutPlane) {
            this.profileBuilder = profileBuilder;
            this.input = input;
            this.buildingsInIntersection = buildingsInIntersection;
            this.cutPlane = cutPlane;
            this.buildings = buildings;
            this.p1 = p1;
            this.p2 = p2;
            seg = new LineSegment(p1, p2).toGeometry(new GeometryFactory());
        }

        @Override
        public void visitItem(Object item) {
            int buildingId = (Integer) item;
            if(!buildingsprocessed.contains(buildingId)) {
                buildingsprocessed.add(buildingId);
                final ProfileBuilder.Building b = buildings.get(buildingId - 1);
                RectangleLineIntersector rect = new RectangleLineIntersector(b.getGeometry().getEnvelopeInternal());
                if (rect.intersects(p1, p2) && b.getGeometry().intersects(seg)) {
                    addBuilding(buildingId);
                }
            }
        }

        public void addBuilding(int buildingId) {
            if (buildingsInIntersection.contains(buildingId)) {
                return;
            }
            List<Coordinate> roofPoints = profileBuilder.getWideAnglePointsByBuilding(buildingId, 0, 2 * Math.PI);
            // Create a cut of the building volume
            roofPoints = cutRoofPointsWithPlane(cutPlane, roofPoints);
            if (!roofPoints.isEmpty()) {
                input.addAll(roofPoints.subList(0, roofPoints.size() - 1));
                buildingsInIntersection.add(buildingId);
                foundIntersection = true;
                // Stop iterating bounding boxes
                throw new IllegalStateException();
            }
        }

        public boolean doContinue() {
            return !foundIntersection;
        }
    }

    public static List<Coordinate> cutRoofPointsWithPlane(Plane plane, List<Coordinate> roofPts) {
        List<Coordinate> polyCut = new ArrayList<>(roofPts.size());
        Double lastOffset = null;
        for (int idp = 0; idp < roofPts.size(); idp++) {
            double offset = plane.getOffset(coordinateToVector(roofPts.get(idp)));
            if (lastOffset != null && ((offset >= 0 && lastOffset < 0) || (offset < 0 && lastOffset >= 0))) {
                // Interpolate vector
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(coordinateToVector(roofPts.get(idp - 1)), coordinateToVector(roofPts.get(idp)), FastObstructionTest.epsilon));
                polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            if (offset >= 0) {
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(roofPts.get(idp).x, roofPts.get(idp).y, Double.MIN_VALUE), coordinateToVector(roofPts.get(idp)), FastObstructionTest.epsilon));
                polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            lastOffset = offset;
        }
        return polyCut;
    }
    public static org.apache.commons.math3.geometry.euclidean.threed.Vector3D coordinateToVector(Coordinate p) {
        return new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p.x, p.y, p.z);
    }

    public PropagationPath computeHorizontalEdgeDiffraction(ProfileBuilder.CutProfile cutProfile, double gs) {
        List<SegmentPath> segments = new ArrayList<>();
        List<SegmentPath> srPath = new ArrayList<>();
        List<PointPath> points = new ArrayList<>();
        List<ProfileBuilder.CutPoint> cutPts = cutProfile.getCutPoints().stream()
                    .filter(cutPoint -> cutPoint.getType() != ProfileBuilder.IntersectionType.GROUND_EFFECT)
                    .collect(Collectors.toList());
        Coordinate src = cutProfile.getSource().getCoordinate();
        Coordinate rcv = cutProfile.getReceiver().getCoordinate();
        LineSegment srcRcvLine = new LineSegment(src, rcv);
        List<ProfileBuilder.CutPoint> pts = new ArrayList<>();
        pts.add(cutProfile.getSource());
        for(int i=1; i<cutPts.size(); i++) {
            ProfileBuilder.CutPoint pt = cutPts.get(i);
            double frac = srcRcvLine.segmentFraction(pt.getCoordinate());
            double z = 0.0;
            for(int j=i+1; j<cutPts.size(); j++) {
                z = Math.max(z, srcRcvLine.p0.z + frac*(cutPts.get(j).getCoordinate().z-srcRcvLine.p0.z));
            }
            if(z <= pt.getCoordinate().z){
                pts.add(pt);
                srcRcvLine = new LineSegment(pt.getCoordinate(), cutProfile.getReceiver().getCoordinate());

                //Filter point to only keep hull.
                List<ProfileBuilder.CutPoint> toRemove = new ArrayList<>();
                //check if last-1 point is under or not the surrounding points
                for(int j = pts.size()-2; j > 0; j--) {
                    //line between last point and previous-1 point
                    LineSegment lineRm = new LineSegment(pts.get(j-1).getCoordinate(), pt.getCoordinate());
                    Coordinate cRm = pts.get(j).getCoordinate();
                    double fracRm = lineRm.segmentFraction(cRm);
                    double zRm = lineRm.p0.z + fracRm * (lineRm.p1.z-lineRm.p0.z);
                    if(zRm >= cRm.z) {
                        toRemove.add(pts.get(j));
                    }
                }
                pts.removeAll(toRemove);
            }
        }
        for (int i = 0; i < pts.size() - 1; i++) {
            segments.add(new SegmentPath(cutProfile.getGS(pts.get(i), pts.get(i+1)),
                    new Vector3D(pts.get(i).getCoordinate(), pts.get(i+1).getCoordinate()),
                    pts.get(i).getCoordinate()));
        }
        for(ProfileBuilder.CutPoint cut : pts) {
            points.add(new PointPath(cut, PointPath.POINT_TYPE.DIFH, gs));
        }
        return new PropagationPath(true, points, segments, srPath);
    }

    private double[] receiverSourcePropa(Coordinate srcCoord, int srcId, double sourceLi,
                                         Coordinate receiverCoord, int rcvId,
                                         IComputeRaysOut dataOut, CnossosPropagationData data) {

        List<PropagationPath> propagationPaths = null;
        double propaDistance = srcCoord.distance(receiverCoord);
        if (propaDistance < data.maxSrcDist) {
            propagationPaths = directPath(srcCoord, srcId, receiverCoord, rcvId, data);

            // Process specular reflection
            if (data.reflexionOrder > 0) {
                List<PropagationPath> propagationPaths_all = computeReflexion(receiverCoord, srcCoord, false, data);
                propagationPaths.addAll(propagationPaths_all);
            }
        }
        if (propagationPaths != null && propagationPaths.size() > 0) {
            return dataOut.addPropagationPaths(srcId, sourceLi, rcvId, propagationPaths);
        }
        return new double[0];
    }

    public List<PropagationPath> computeReflexion(Coordinate rcvCoord,
                                                  Coordinate srcCoord, boolean favorable,
                                                  CnossosPropagationData data) {

        // Compute receiver mirror
        LineSegment srcRcvLine = new LineSegment(srcCoord, rcvCoord);
        LineIntersector linters = new RobustLineIntersector();
        List<MirrorReceiverResult> mirrorResults = new ArrayList<>();
        //Keep only building walls which are not too far.
        List<ProfileBuilder.Wall> buildWalls = data.profileBuilder.getProcessedWalls().stream()
                .filter(wall -> wall.getType().equals(ProfileBuilder.IntersectionType.BUILDING))
                .filter(wall -> wall.getLine().distance(srcRcvLine) < data.maxRefDist)
                .collect(Collectors.toList());

        for(ProfileBuilder.Wall wall : buildWalls) {
            //Calculate the coordinate of the mirror rcv
            Coordinate proj = wall.getLine().project(rcvCoord);
            Coordinate rcvMirror = new Coordinate(2*proj.x-rcvCoord.x, 2*proj.y-rcvCoord.y, rcvCoord.z);
            //If the mirror rcv is too far, skip it
            if(srcRcvLine.p0.distance(rcvMirror) > data.maxSrcDist) {
                continue;
            }
            //Check if a other wall is masking the current
            LineSegment srcMirrRcvLine = new LineSegment(srcCoord, rcvMirror);
            Coordinate inter = srcMirrRcvLine.intersection(wall.getLine());
            if(inter == null) {
                continue;
            }
            double frac = wall.getLine().segmentFraction(inter);
            double interZ = wall.getLine().p0.z + frac * (wall.getLine().p1.z-wall.getLine().p0.z);
            double dist = new LineSegment(srcCoord, inter).getLength();
            boolean skipWall = false;
            List<ProfileBuilder.Wall> walls = buildWalls.stream().filter(w -> w.getOriginId() == wall.getOriginId()).collect(Collectors.toList());
            for(ProfileBuilder.Wall otherWall : walls) {
                Coordinate otherInter = srcMirrRcvLine.intersection(otherWall.getLine());
                if(otherInter != null) {
                    double otherFrac = otherWall.getLine().segmentFraction(otherInter);
                    double otherInterZ = otherWall.getLine().p0.z + otherFrac * (otherWall.getLine().p1.z-otherWall.getLine().p0.z);
                    double d1 = srcMirrRcvLine.segmentFraction(inter);
                    double d2 = srcMirrRcvLine.segmentFraction(otherInter);
                    //if(otherInterZ > d2*interZ/d1) {
                    if(otherInterZ > d2*interZ/d1) {
                        double otherDist = new LineSegment(srcCoord, otherInter).getLength();
                        if (otherDist < dist) {
                            skipWall = true;
                            break;
                        }
                    }
                }
            }
            if(!skipWall) {
                mirrorResults.add(new MirrorReceiverResult(rcvMirror, null,
                        data.profileBuilder.getProcessedWalls().indexOf(wall), wall.getOriginId()));
            }
        }

        List<PropagationPath> reflexionPropagationPaths = new ArrayList<>();

        for (MirrorReceiverResult receiverReflection : mirrorResults) {
            ProfileBuilder.Wall seg = buildWalls.get(receiverReflection.getWallId());
            List<MirrorReceiverResult> rayPath = new ArrayList<>(data.reflexionOrder + 2);
            boolean validReflection = false;
            MirrorReceiverResult receiverReflectionCursor = receiverReflection;
            // Test whether intersection point is on the wall
            // segment or not
            Coordinate destinationPt = new Coordinate(srcCoord);

            linters.computeIntersection(seg.getLine().p0, seg.getLine().p1,
                    receiverReflection.getReceiverPos(),
                    destinationPt);
            while (linters.hasIntersection() /*&& MirrorReceiverIterator.wallPointTest(seg.getLine(), destinationPt)*/) {
                // There are a probable reflection point on the segment
                Coordinate reflectionPt = new Coordinate(
                        linters.getIntersection(0));
                if (reflectionPt.equals(destinationPt)) {
                    break;
                }
                Coordinate vec_epsilon = new Coordinate(
                        reflectionPt.x - destinationPt.x,
                        reflectionPt.y - destinationPt.y);
                double length = vec_epsilon
                        .distance(new Coordinate(0., 0., 0.));
                // Normalize vector
                vec_epsilon.x /= length;
                vec_epsilon.y /= length;
                // Multiply by epsilon in meter
                vec_epsilon.x *= FastObstructionTest.wideAngleTranslationEpsilon;
                vec_epsilon.y *= FastObstructionTest.wideAngleTranslationEpsilon;
                // Translate reflection pt by epsilon to get outside
                // the wall
                reflectionPt.x -= vec_epsilon.x;
                reflectionPt.y -= vec_epsilon.y;
                // Compute Z interpolation
                reflectionPt.setOrdinate(Coordinate.Z, Vertex.interpolateZ(linters.getIntersection(0),
                        receiverReflectionCursor.getReceiverPos(), destinationPt));

                // Test if there is no obstacles between the
                // reflection point and old reflection pt (or source position)
                validReflection = Double.isNaN(receiverReflectionCursor.getReceiverPos().z) ||
                        Double.isNaN(reflectionPt.z) || Double.isNaN(destinationPt.z) /*|| seg.getOriginId() == 0*/
                        || (reflectionPt.z < data.profileBuilder.getBuilding(seg.getOriginId()).getGeometry().getCoordinate().z
                        && reflectionPt.z > data.profileBuilder.getTopoZ(reflectionPt)
                        && destinationPt.z > data.profileBuilder.getTopoZ(destinationPt));
                if (validReflection) // Source point can see receiver image
                {
                    MirrorReceiverResult reflResult = new MirrorReceiverResult(receiverReflectionCursor);
                    reflResult.setReceiverPos(reflectionPt);
                    rayPath.add(reflResult);
                    if (receiverReflectionCursor
                            .getParentMirror() == null) { // Direct to the receiver
                        break; // That was the last reflection
                    } else {
                        // There is another reflection
                        destinationPt.setCoordinate(reflectionPt);
                        // Move reflection information cursor to a
                        // reflection closer
                        receiverReflectionCursor = receiverReflectionCursor.getParentMirror();
                        // Update intersection data
                        seg = buildWalls
                                .get(receiverReflectionCursor
                                        .getWallId());
                        linters.computeIntersection(seg.getLine().p0, seg.getLine().p1,
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
            if (validReflection && !rayPath.isEmpty()) {
                // Check intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiverResult refl = rayPath.get(idPt + 1);
                    ProfileBuilder.CutProfile profile = data.profileBuilder.getProfile(firstPt, refl.getReceiverPos(), data.gS);
                    if (!profile.intersectTopography() && !profile.intersectBuilding() ) {
                        validReflection = false;
                        break;
                    }
                }
                if (!validReflection) {
                    continue;
                }
                // A valid propagation path as been found
                List<PointPath> points = new ArrayList<PointPath>();
                List<SegmentPath> segments = new ArrayList<SegmentPath>();
                List<SegmentPath> srPath = new ArrayList<SegmentPath>();
                // Compute direct path between source and first reflection point, add profile to the data
                computeReflexionOverBuildings(srcCoord, rayPath.get(0).getReceiverPos(), points, segments, srPath, data);
                if (points.isEmpty()) {
                    continue;
                }
                PointPath reflPoint = points.get(points.size() - 1);
                reflPoint.setType(PointPath.POINT_TYPE.REFL);
                reflPoint.setBuildingId(rayPath.get(0).getBuildingId());
                reflPoint.setAlphaWall(data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getAlphas());
                // Add intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiverResult refl = rayPath.get(idPt + 1);
                    reflPoint = new PointPath(refl.getReceiverPos(), 0, 1, data.profileBuilder.getBuilding(refl.getBuildingId()).getAlphas(), refl.getBuildingId(), PointPath.POINT_TYPE.REFL);
                    points.add(reflPoint);
                    segments.add(new SegmentPath(1, new Vector3D(firstPt), refl.getReceiverPos()));
                }
                // Compute direct path between receiver and last reflection point, add profile to the data
                List<PointPath> lastPts = new ArrayList<>();
                computeReflexionOverBuildings(rayPath.get(rayPath.size() - 1).getReceiverPos(), rcvCoord, lastPts, segments, srPath, data);
                if (lastPts.isEmpty()) {
                    continue;
                }
                points.addAll(lastPts.subList(1, lastPts.size()));
                for (int i = 1; i < points.size(); i++) {
                    if (points.get(i).type == PointPath.POINT_TYPE.REFL) {
                        if (i < points.size() - 1) {
                            // A diffraction point may have offset in height the reflection coordinate
                            points.get(i).coordinate.z = Vertex.interpolateZ(points.get(i).coordinate, points.get(i - 1).coordinate, points.get(i + 1).coordinate);
                            //check if in building && if under floor
                            if (points.get(i).coordinate.z > data.profileBuilder.getBuilding(points.get(i).getBuildingId()).getGeometry().getCoordinate().z
                                    || points.get(i).coordinate.z <= data.profileBuilder.getTopoZ(points.get(i).coordinate)) {
                                points.clear();
                                segments.clear();
                                break;
                            }
                        } else {
                            LOGGER.warn("Invalid state, reflexion point on last point");
                            points.clear();
                            segments.clear();
                            break;
                        }
                    }
                }
                if (points.size() > 2) {
                    reflexionPropagationPaths.add(new PropagationPath(favorable, points, segments, srPath));
                }
            }
        }
        return reflexionPropagationPaths;
    }


    public void computeReflexionOverBuildings(Coordinate p0, Coordinate p1, List<PointPath> points, List<SegmentPath> segments, List<SegmentPath> srPath, CnossosPropagationData data) {
        List<PropagationPath> propagationPaths = directPath(p0, -1, p1, -1, data);
        if (!propagationPaths.isEmpty()) {
            PropagationPath propagationPath = propagationPaths.get(0);
            points.addAll(propagationPath.getPointList());
            segments.addAll(propagationPath.getSegmentList());
            srPath.add(new SegmentPath(1.0, new Vector3D(p0, p1), p0));
        }
    }

    private static double insertPtSource(Coordinate receiverPos, Coordinate ptpos, double[] wj, double li, Integer sourceId, List<SourcePointInfo> sourceList) {
        // Compute maximal power at freefield at the receiver position with reflective ground
        double aDiv = -getADiv(CGAlgorithms3D.distance(receiverPos, ptpos));
        double[] srcWJ = new double[wj.length];
        for (int idFreq = 0; idFreq < srcWJ.length; idFreq++) {
            srcWJ[idFreq] = wj[idFreq] * li * dbaToW(aDiv) * dbaToW(3);
        }
        sourceList.add(new SourcePointInfo(srcWJ, sourceId, ptpos, li));
        return ComputeRays.sumArray(srcWJ.length, srcWJ);
    }

    private double addLineSource(LineString source, Coordinate receiverCoord, int srcIndex, List<SourcePointInfo> sourceList, double[] wj, CnossosPropagationData data) {
        double totalPowerRemaining = 0;
        ArrayList<Coordinate> pts = new ArrayList<Coordinate>();
        // Compute li to equation 4.1 NMPB 2008 (June 2009)
        Coordinate nearestPoint = JTSUtility.getNearestPoint(receiverCoord, source);
        double segmentSizeConstraint = Math.max(1, receiverCoord.distance3D(nearestPoint) / 2.0);
        if (Double.isNaN(segmentSizeConstraint)) {
            segmentSizeConstraint = Math.max(1, receiverCoord.distance(nearestPoint) / 2.0);
        }
        double li = splitLineStringIntoPoints(source, segmentSizeConstraint, pts);
        for (Coordinate pt : pts) {
            if (pt.distance(receiverCoord) < data.maxSrcDist) {
                totalPowerRemaining += insertPtSource(receiverCoord, pt, wj, li, srcIndex, sourceList);
            }
        }
        return totalPowerRemaining;
    }

    public void computeRaysAtPosition(Coordinate receiverCoord, int idReceiver, CnossosPropagationData data, IComputeRaysOut dataOut, ProgressVisitor progressVisitor) {
        //Compute the source search area
        double searchSourceDistance = data.maxSrcDist;
        Envelope receiverSourceRegion = new Envelope(receiverCoord.x
                - searchSourceDistance, receiverCoord.x + searchSourceDistance,
                receiverCoord.y - searchSourceDistance, receiverCoord.y
                + searchSourceDistance
        );
        Iterator<Integer> regionSourcesLst = data.sourcesIndex.query(receiverSourceRegion);
        List<SourcePointInfo> sourceList = new ArrayList<>();
        // Sum of all sources power using only geometric dispersion with direct field
        //double totalPowerRemaining = 0;
        HashSet<Integer> processedLineSources = new HashSet<>(); //Already processed Raw source (line and/or points)
        while (regionSourcesLst.hasNext()) {
            Integer srcIndex = regionSourcesLst.next();
            if (!processedLineSources.contains(srcIndex)) {
                processedLineSources.add(srcIndex);
                Geometry source = data.sourceGeometries.get(srcIndex);
                double[] wj = data.getMaximalSourcePower(srcIndex);
                if (source instanceof Point) {
                    Coordinate ptpos = source.getCoordinate();
                    if (ptpos.distance(receiverCoord) < data.maxSrcDist) {
                        insertPtSource(receiverCoord, ptpos, wj, 1., srcIndex, sourceList);
                    }
                } else if (source instanceof LineString) {
                    addLineSource((LineString) source, receiverCoord, srcIndex, sourceList, wj, data);
                } else if (source instanceof MultiLineString) {
                    for (int id = 0; id < source.getNumGeometries(); id++) {
                        Geometry subGeom = source.getGeometryN(id);
                        if (subGeom instanceof LineString) {
                            addLineSource((LineString) subGeom, receiverCoord, srcIndex, sourceList, wj, data);
                        }
                    }
                } else {
                    throw new IllegalArgumentException(String.format("Sound source %s geometry are not supported", source.getGeometryType()));
                }
            }
        }
        // Sort sources by power contribution descending
        Collections.sort(sourceList);
        double powerAtSource = 0;
        for (SourcePointInfo src : sourceList) {
            // For each Pt Source - Pt Receiver
            Coordinate srcCoord = src.position;
            receiverSourcePropa(srcCoord, src.sourcePrimaryKey, src.li, receiverCoord, idReceiver, dataOut, data);

            // If the delta between already received power and maximal potential power received is inferior than than data.maximumError
            if ((progressVisitor != null && progressVisitor.isCanceled())) {
                break; //Stop looking for more rays
            }
        }
        // No more rays for this receiver
        dataOut.finalizeReceiver(idReceiver);
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

private static final class RangeReceiversComputation implements Runnable {
    private final int startReceiver; // Included
    private final int endReceiver; // Excluded
    private final ComputeCnossosRays propagationProcess;
    private final ProgressVisitor progressVisitor;
    private final IComputeRaysOut dataOut;
    private final CnossosPropagationData data;

    public RangeReceiversComputation(int startReceiver, int endReceiver, ComputeCnossosRays propagationProcess,
                                     ProgressVisitor progressVisitor, IComputeRaysOut dataOut,
                                     CnossosPropagationData data) {
        this.startReceiver = startReceiver;
        this.endReceiver = endReceiver;
        this.propagationProcess = propagationProcess;
        this.progressVisitor = progressVisitor;
        this.dataOut = dataOut;
        this.data = data;
    }

    @Override
    public void run() {
        try {
            for (int idReceiver = startReceiver; idReceiver < endReceiver; idReceiver++) {
                if (progressVisitor != null) {
                    if (progressVisitor.isCanceled()) {
                        break;
                    }
                }
                Coordinate receiverCoord = data.receivers.get(idReceiver);

                propagationProcess.computeRaysAtPosition(receiverCoord, idReceiver, data, dataOut, progressVisitor);

                if (progressVisitor != null) {
                    progressVisitor.endStep();
                }
            }
        } catch (Exception ex) {
            LOGGER.error(ex.getLocalizedMessage(), ex);
            if (progressVisitor != null) {
                progressVisitor.cancel();
            }
            throw ex;
        }
    }
}

private static final class SourcePointInfo implements Comparable<SourcePointInfo> {
    private double[] wj;
    private double li; //
    private int sourcePrimaryKey;
    private Coordinate position;
    private double globalWj;

    /**
     * @param wj               Maximum received power from this source
     * @param sourcePrimaryKey
     * @param position
     */
    public SourcePointInfo(double[] wj, int sourcePrimaryKey, Coordinate position, double li) {
        this.wj = wj;
        this.sourcePrimaryKey = sourcePrimaryKey;
        this.position = position;
        if (Double.isNaN(position.z)) {
            this.position = new Coordinate(position.x, position.y, 0);
        }
        this.globalWj = sumArray(wj.length, wj);
        this.li = li;
    }

    /**
     * @return coefficient to apply to linear source as sound power per meter length
     */
    public double getLi() {
        return li;
    }

    public double[] getWj() {
        return wj;
    }

    public void setWj(double[] wj) {
        this.wj = wj;
        this.globalWj = sumArray(wj.length, wj);
    }

    @Override
    public int compareTo(SourcePointInfo sourcePointInfo) {
        int cmp = -Double.compare(globalWj, sourcePointInfo.globalWj);
        if (cmp == 0) {
            return Integer.compare(sourcePrimaryKey, sourcePointInfo.sourcePrimaryKey);
        } else {
            return cmp;
        }
    }
}
}