/**
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 *
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 *
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 *
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 *
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.noise_planet.noisemodelling.propagation;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;

import junit.framework.TestCase;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;

public class TestWallReflection extends TestCase {

	public void testWallVisibility () {
		// 
		// a0_____a1
		// |      |    
		// |build1|      
		// |      |    b0____b1
		// |______|   /      /
		// a3     a2 /buil2d/    c2
		//          /______/b2  /
		//          b3         /
		//                    /
		// c0________________/c1
		Coordinate a0=new Coordinate(0,9);
		Coordinate a1=new Coordinate(4,9);
		Coordinate a2=new Coordinate(4,4);
		Coordinate a3=new Coordinate(0,4);
		Coordinate b0=new Coordinate(7,5);
		Coordinate b1=new Coordinate(11,5);
		Coordinate b2=new Coordinate(9,0);
		Coordinate b3=new Coordinate(5,0);
		
		Coordinate c0=new Coordinate(0,-2);
		Coordinate c1=new Coordinate(12,-2);
		Coordinate c2=new Coordinate(16,3);
		
		
		LineSegment a01=new LineSegment(a0,a1);
		LineSegment a12=new LineSegment(a1,a2);
		LineSegment a23=new LineSegment(a2,a3);
		LineSegment a30=new LineSegment(a3,a0);
		
		LineSegment b01=new LineSegment(b0,b1);
		LineSegment b12=new LineSegment(b1,b2);
		LineSegment b23=new LineSegment(b2,b3);
		LineSegment b30=new LineSegment(b3,b0);

		LineSegment c01=new LineSegment(c0,c1);
		LineSegment c12=new LineSegment(c1,c2);
		//Test cases Walls face to face
		assertTrue(MirrorReceiverIterator.wallWallTest(b30, a23));
		assertTrue(MirrorReceiverIterator.wallWallTest(b30, a12));
		assertTrue(MirrorReceiverIterator.wallWallTest(b01, a12));
		assertTrue(MirrorReceiverIterator.wallWallTest(c01, c12));
		assertTrue(MirrorReceiverIterator.wallWallTest(c12, a12));
		
		//Test cases Walls hidden
		assertFalse(MirrorReceiverIterator.wallWallTest(b30, b12));
		assertFalse(MirrorReceiverIterator.wallWallTest(b30, a01));
		assertFalse(MirrorReceiverIterator.wallWallTest(b30, a30));
		assertFalse(MirrorReceiverIterator.wallWallTest(b23, a23));
		assertFalse(MirrorReceiverIterator.wallWallTest(b12, a23));
		assertFalse(MirrorReceiverIterator.wallWallTest(b30, b01));
		assertFalse(MirrorReceiverIterator.wallWallTest(b30, b23));
		
	}

    public void testWallReceiverImage() {
        Coordinate a = new Coordinate(2, 3);
        Coordinate b = new Coordinate(6, 3);
        Coordinate c = new Coordinate(2, 1);
        Coordinate d = new Coordinate(6, 1);
        Coordinate e = new Coordinate(3, 7);
        Coordinate f = new Coordinate(7, 7);
        Coordinate g = new Coordinate(3, 5);
        Coordinate h = new Coordinate(7, 5);
        List<FastObstructionTest.Wall> walls = new ArrayList<>(8);
        // Order of walls points must be counter clock wise (from exterior of building)
        // Building 1
        walls.add(new FastObstructionTest.Wall(a, b, 0));
        walls.add(new FastObstructionTest.Wall(b, d, 0));
        walls.add(new FastObstructionTest.Wall(d, c, 0));
        walls.add(new FastObstructionTest.Wall(c, a, 0));
        // Building 2
        walls.add(new FastObstructionTest.Wall(g, e, 1));
        walls.add(new FastObstructionTest.Wall(h, g, 1));
        walls.add(new FastObstructionTest.Wall(f, h, 1));
        walls.add(new FastObstructionTest.Wall(e, f, 1));
        Coordinate receiver = new Coordinate(0, 4);
        Coordinate source = new Coordinate(9, 4);

        MirrorReceiverIterator.It mirrorReceiverResults =
                new MirrorReceiverIterator.It(receiver, walls, new LineSegment(source, receiver), 20, 2, 40);
        Iterator<MirrorReceiverResult> it = mirrorReceiverResults.iterator();
        wallTest(new Coordinate(0, 2), new int[]{0}, it.next());
        wallTest(new Coordinate(6, 2), new int[]{0, 4}, it.next());
        wallTest(new Coordinate(0, 8), new int[]{0, 5}, it.next());
        wallTest(new Coordinate(4, 4), new int[]{3}, it.next());
        wallTest(new Coordinate(6, 4), new int[]{4}, it.next());
        wallTest(new Coordinate(6, 2), new int[]{4, 0}, it.next());
        wallTest(new Coordinate(0, 6), new int[]{5}, it.next());
        wallTest(new Coordinate(0, 0), new int[]{5, 0}, it.next());
        assertFalse(it.hasNext());
    }

    private void wallTest(Coordinate expectedCoordinate,int[] expectedWalls,MirrorReceiverResult res) {
        int[] resultWalls = new int[expectedWalls.length];
        int id = 0;
        MirrorReceiverResult cursor = res;
        while(cursor != null) {
            if(expectedWalls.length - 1 - id >= 0) {
                resultWalls[expectedWalls.length - 1 - id] = cursor.getWallId();
            }
            cursor = cursor.getParentMirror();
            id++;
        }
        assertArrayEquals(expectedWalls, resultWalls);
        assertEquals(expectedCoordinate, res.getReceiverPos());
    }

    private void equalsTest(int[] expected, List<Integer> result) {
        for(int i=0;i<result.size();i++) {
            assertEquals(expected[i], result.get(i).intValue());
        }
    }


    public void testWallIndexIt() {
        MirrorReceiverIterator.CrossTableIterator it = new MirrorReceiverIterator.CrossTableIterator(2, 3);
        equalsTest(new int[]{0}, it.next());
        equalsTest(new int[]{0,1}, it.next());
        equalsTest(new int[]{0,2}, it.next());
        equalsTest(new int[]{1}, it.next());
        equalsTest(new int[]{1,0}, it.next());
        equalsTest(new int[]{1,2}, it.next());
        equalsTest(new int[]{2}, it.next());
        equalsTest(new int[]{2,0}, it.next());
        equalsTest(new int[]{2,1}, it.next());
        assertFalse(it.hasNext());
    }


    public void testWallIndexItSkip() {
        MirrorReceiverIterator.CrossTableIterator it = new MirrorReceiverIterator.CrossTableIterator(2, 3);
        equalsTest(new int[]{0}, it.next());
        equalsTest(new int[]{0,1}, it.next());
        equalsTest(new int[]{0, 2}, it.next());
        equalsTest(new int[]{1}, it.next());
        it.skipLevel();
        equalsTest(new int[]{2}, it.next());
        equalsTest(new int[]{2, 0}, it.next());
        it.skipLevel();
        assertFalse(it.hasNext());
    }

    public void testSingleWall() {
        MirrorReceiverIterator.CrossTableIterator it = new MirrorReceiverIterator.CrossTableIterator(2, 1);
        equalsTest(new int[]{0}, it.next());
        assertFalse(it.hasNext());
    }

    public void testPath() throws ParseException, LayerDelaunayError {

        GeometryFactory factory = new GeometryFactory();
        WKTReader wktReader = new WKTReader(factory);
        //Scene dimension
        Envelope cellEnvelope = wktReader.read("POLYGON ((316849.05 6703855.11, 316849.05 6703924.04, " +
                "316925.36 6703924.04, 316925.36 6703855.11, 316849.05 6703855.11))").getEnvelopeInternal();
        Coordinate p1 = new Coordinate(316914.1, 6703907.5, 4);
        Coordinate p2 = new Coordinate(316913.4, 6703879, 4);
        //Create obstruction test object
        MeshBuilder mesh = new MeshBuilder();

        mesh.addGeometry(wktReader.read("POLYGON ((316900.21711186244 6703891.837263795, 316903.24859771173 6703904.468454833, 316926.44405761914 6703898.451414739, 316925.433562336 6703889.678478417, 316914.1343878069 6703892.480306247, 316913.53727695777 6703890.367452473, 316906.78533120255 6703892.066921813, 316906.4178783723 6703890.32152087, 316900.21711186244 6703891.837263795))"), 11.915885805791621);
        mesh.addGeometry(wktReader.read("POLYGON ((316886.41 6703903.61, 316888.31 6703910.59, 316899.79 6703907.69, 316897.99 6703900.71, 316886.41 6703903.61))"), 13.143551238469575);

        mesh.finishPolygonFeeding(cellEnvelope);


        //Retrieve Delaunay triangulation of scene
        FastObstructionTest manager = new FastObstructionTest(mesh.getPolygonWithHeight(), mesh.getTriangles(), mesh.getTriNeighbors(), mesh.getVertices());

        PropagationProcessData data = new PropagationProcessData(manager);
	    ComputeRays computeRays = new ComputeRays(data);
	    computeRays.initStructures();
        data.reflexionOrder = 1;
	    Coordinate receiver = new Coordinate(316898.0027227718, 6703891.69841584, 4);
	    Coordinate source = new Coordinate(316900.8845049501,6703903.754851485, 0.05);
        List<FastObstructionTest.Wall> walls = data.freeFieldFinder.getLimitsInRange(
                data.maxRefDist, source, false);
        assertEquals(12, walls.size());
        List<PropagationPath> paths;
        paths = computeRays.computeReflexion(receiver, source, false, walls);
        assertEquals(1, paths.size());
        List<PointPath> pts = paths.get(0).getPointList();
        assertEquals(3, pts.size());
        assertEquals(PointPath.POINT_TYPE.SRCE, pts.get(0).type);
        assertEquals(0, source.distance(pts.get(0).coordinate), 1e-6);
        assertEquals(PointPath.POINT_TYPE.REFL, pts.get(1).type);
        assertEquals(0, new Coordinate(316901.506, 6703897.22, 2.14).distance(pts.get(1).coordinate), 0.01);
        assertEquals(PointPath.POINT_TYPE.RECV, pts.get(2).type);
        assertEquals(0, receiver.distance(pts.get(2).coordinate), 1e-6);

        data.reflexionOrder = 2;
        paths = computeRays.computeReflexion(receiver, source, false, walls);
        assertEquals(2, paths.size());
        pts = paths.get(1).getPointList();
        // 2 ref points
        assertEquals(4, pts.size());
        assertEquals(PointPath.POINT_TYPE.SRCE, pts.get(0).type);
        assertEquals(0, source.distance(pts.get(0).coordinate), 1e-6);
        assertEquals(PointPath.POINT_TYPE.REFL, pts.get(1).type);
        assertEquals(0, new Coordinate(316898.18, 6703901.42, 0.99).distance(pts.get(1).coordinate), 0.01);
        assertEquals(PointPath.POINT_TYPE.REFL, pts.get(2).type);
        assertEquals(0, new Coordinate(316900.80, 6703894.30, 3).distance(pts.get(2).coordinate), 0.01);
        assertEquals(PointPath.POINT_TYPE.RECV, pts.get(3).type);
        assertEquals(0, receiver.distance(pts.get(3).coordinate), 1e-6);
    }
}
