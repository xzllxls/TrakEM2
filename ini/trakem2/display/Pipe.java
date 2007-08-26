/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;

import ini.trakem2.Project;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Search;
import ini.trakem2.utils.Vector3;
import ini.trakem2.persistence.DBObject;
import ini.trakem2.vector.SV;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Polygon;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

import javax.vecmath.Point3f;


public class Pipe extends ZDisplayable {

	/**The number of points.*/
	protected int n_points;
	/**The array of clicked points.*/
	protected double[][] p;
	/**The array of left control points, one for each clicked point.*/
	protected double[][] p_l;
	/**The array of right control points, one for each clicked point.*/
	protected double[][] p_r;
	/**The array of interpolated points generated from p, p_l and p_r.*/
	protected double[][] p_i = new double[2][0];
	/**The array of Layers over which the points of this pipe live */
	protected long[] p_layer;
	/**The width of each point. */
	protected double[] p_width;
	/**The interpolated width for each interpolated point. */
	protected double[] p_width_i = new double[0];

	public Pipe(Project project, String title, double x, double y) {
		super(project, title, x, y);
		n_points = 0;
		p = new double[2][5];
		p_l = new double[2][5];
		p_r = new double[2][5];
		p_layer = new long[5]; // the ids of the layers in which each point lays
		p_width = new double[5];
		addToDatabase();
	}

	/** Construct an unloaded Pipe from the database. Points will be loaded later, when needed. */
	public Pipe(Project project, long id, String title, double width, double height, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, id, title, locked, at, width, height);
		this.visible = visible;
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.n_points = -1; //used as a flag to signal "I have points, but unloaded"
	}

	/** Construct a Pipe from an XML entry. */
	public Pipe(Project project, long id, Hashtable ht, Hashtable ht_links) {
		super(project, id, ht, ht_links);
		// parse specific data
		for (java.util.Enumeration e = ht.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht.get(key);
			if (key.equals("d")) {
				// parse the points
				// parse the SVG points data
				ArrayList al_p = new ArrayList();
				ArrayList al_p_r = new ArrayList();
				ArrayList al_p_l = new ArrayList();// needs shifting, inserting one point at the beginning if not closed.
				// sequence is: M p[0],p[1] C p_r[0],p_r[1] p_l[0],p_l[1] and repeat without the M, and finishes with the last p[0],p[1]. If closed, appended at the end is p_r[0],p_r[1] p_l[0],p_l[1]
				// first point:
				int i_start = data.indexOf('M');
				int i_end = data.indexOf('C');
				String point = data.substring(i_start+1, i_end).trim();
				al_p.add(point);
				boolean go = true;
				while (go) {
					i_start = i_end;
					i_end = data.indexOf('C', i_end+1);
					if (-1 == i_end) {
						i_end = data.length() -1;
						go = false;
					}
					String txt = data.substring(i_start+1, i_end).trim();
					// eliminate double spaces
					while (-1 != txt.indexOf("  ")) {
						txt = txt.replaceAll("  ", " ");
					}
					// reduce ", " and " ," to ","
					txt = txt.replaceAll(" ,", ",");
					txt = txt.replaceAll(", ", ",");
					Utils.log("Parsing pipe: " + txt);
					// cut by spaces
					String[] points = txt.split(" ");
					if (3 == points.length) {
						al_p_r.add(points[0]);
						al_p_l.add(points[1]);
						al_p.add(points[2]);
					} else {
						// error
						Utils.log("Pipe constructor from XML: error at parsing points.");
					}
					// example: C 34.5,45.6 45.7,23.0 34.8, 78.0 C ..
				}
				// fix missing control points
				al_p_l.add(0, al_p.get(0));
				al_p_r.add(al_p.get(al_p.size() -1));
				// sanity check:
				if (!(al_p.size() == al_p_l.size() && al_p_l.size() == al_p_r.size())) {
					Utils.log2("Pipe XML parsing: Disagreement in the number of points:\n\tp.length=" + al_p.size() + "\n\tp_l.length=" + al_p_l.size() + "\n\tp_r.length=" + al_p_r.size());
				}
				// Now parse the points
				this.n_points = al_p.size();
				this.p = new double[2][n_points];
				this.p_l = new double[2][n_points];
				this.p_r = new double[2][n_points];
				for (int i=0; i<n_points; i++) {
					String[] sp = ((String)al_p.get(i)).split(",");
					p[0][i] = Double.parseDouble(sp[0]);
					p[1][i] = Double.parseDouble(sp[1]);
					sp = ((String)al_p_l.get(i)).split(",");
					p_l[0][i] = Double.parseDouble(sp[0]);
					p_l[1][i] = Double.parseDouble(sp[1]);
					sp = ((String)al_p_r.get(i)).split(",");
					p_r[0][i] = Double.parseDouble(sp[0]);
					p_r[1][i] = Double.parseDouble(sp[1]);
				}
			} else if (key.equals("layer_ids")) {
				// parse comma-separated list of layer ids. Creates empty Layer instances with the proper id, that will be replaced later.
				String[] layer_ids = data.replaceAll(" ", "").trim().split(",");
				this.p_layer = new long[layer_ids.length];
				for (int i=0; i<layer_ids.length; i++) {
					this.p_layer[i] = Long.parseLong(layer_ids[i]);
				}
			} else if (key.equals("p_width")) {
				String[] widths = data.replaceAll(" ", "").trim().split(",");
				this.p_width = new double[widths.length];
				for (int i=0; i<widths.length; i++) {
					this.p_width[i] = Double.parseDouble(widths[i]);
				}
			}
		}
		// finish up
		this.p_i = new double[2][0]; // empty
		this.p_width_i = new double[0];
		generateInterpolatedPoints(0.05);
		// sanity check:
		if (!(n_points == p.length && p.length == p_width.length && p_width.length == p_layer.length)) {
			Utils.log2("Pipe at parsing XML: inconsistent number of points for id=" + id);
		}
	}

	/**Increase the size of the arrays by 5.*/
	private void enlargeArrays() {
		//catch length
		int length = p[0].length;
		//make copies
		double[][] p_copy = new double[2][length + 5];
		double[][] p_l_copy = new double[2][length + 5];
		double[][] p_r_copy = new double[2][length + 5];
		long[] p_layer_copy = new long[length + 5];
		double[] p_width_copy = new double[length + 5];
		//copy values
		System.arraycopy(p[0], 0, p_copy[0], 0, length);
		System.arraycopy(p[1], 0, p_copy[1], 0, length);
		System.arraycopy(p_l[0], 0, p_l_copy[0], 0, length);
		System.arraycopy(p_l[1], 0, p_l_copy[1], 0, length);
		System.arraycopy(p_r[0], 0, p_r_copy[0], 0, length);
		System.arraycopy(p_r[1], 0, p_r_copy[1], 0, length);
		System.arraycopy(p_layer, 0, p_layer_copy, 0, length);
		System.arraycopy(p_width, 0, p_width_copy, 0, length);
		//assign them
		this.p = p_copy;
		this.p_l = p_l_copy;
		this.p_r = p_r_copy;
		this.p_layer = p_layer_copy;
		this.p_width = p_width_copy;
	}

	/**Find a point in an array, with a precision dependent on the magnification. Only points in the current layer are found, the rest are ignored.*/
	protected int findPoint(double[][] a, int x_p, int y_p, double magnification) {
		int index = -1;
		double d = (7.0D / magnification);
		double min_dist = Double.MAX_VALUE;
		long i_layer = Display.getFrontLayer(this.project).getId();
		for (int i=0; i<n_points; i++) {
			double dist = Math.abs(x_p - a[0][i]) + Math.abs(y_p - a[1][i]);
			if (i_layer == p_layer[i] && dist <= d && dist <= min_dist) {
				min_dist = dist;
				index = i;
				
			}
		}
		return index;
	}

	/**Remove a point from the bezier backbone and its two associated control points.*/
	protected void removePoint(int index) {
		// check preconditions:
		if (index < 0) {
			return;
		} else if (n_points - 1 == index) {
			//last point out
			n_points--;
		} else {
			//one point out (but not the last)
			--n_points;

			// shift all points after 'index' one position to the left:
			for (int i=index; i<n_points; i++) {
				p[0][i] = p[0][i+1];		//the +1 doesn't fail ever because the n_points has been adjusted above, but the arrays are still the same size. The case of deleting the last point is taken care above.
				p[1][i] = p[1][i+1];
				p_l[0][i] = p_l[0][i+1];
				p_l[1][i] = p_l[1][i+1];
				p_r[0][i] = p_r[0][i+1];
				p_r[1][i] = p_r[1][i+1];
				p_layer[i] = p_layer[i+1];
				p_width[i] = p_width[i+1];
			}
		}

		//update in database
		updateInDatabase("points");
	}
	/**Calculate distance from one point to another.*/
	protected double distance(double x1, double y1, double x2, double y2) {
		return (Math.sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1)));
	}

	/**Move backbone point by the given deltas.*/
	protected void dragPoint(int index, int dx, int dy) {
		p[0][index] += dx;
		p[1][index] += dy;
		p_l[0][index] += dx;
		p_l[1][index] += dy;
		p_r[0][index] += dx;
		p_r[1][index] += dy;
	}

	/**Set the control points to the same value as the backbone point which they control.*/
	protected void resetControlPoints(int index) {
		p_l[0][index] = p[0][index];
		p_l[1][index] = p[1][index];
		p_r[0][index] = p[0][index];
		p_r[1][index] = p[1][index];
	}
	/**Drag a control point and adjust the other, dependent one, in a symmetric way or not.*/
	protected void dragControlPoint(int index, int x_d, int y_d, double[][] p_dragged, double[][] p_adjusted, boolean symmetric) {
		//measure hypothenusa: from p to p control
		double hypothenusa;
		if (symmetric) {
			//make both points be dragged in parallel, the same distance
			hypothenusa = distance(p[0][index], p[1][index], p_dragged[0][index], p_dragged[1][index]);
		} else {
			//make each point be dragged with its own distance
			hypothenusa = distance(p[0][index], p[1][index], p_adjusted[0][index], p_adjusted[1][index]);
		}
		//measure angle: use the point being dragged
		double angle = Math.atan2(p_dragged[0][index] - p[0][index], p_dragged[1][index] - p[1][index]) + Math.PI;
		//apply
		p_dragged[0][index] = x_d;
		p_dragged[1][index] = y_d;
		p_adjusted[0][index] = p[0][index] + hypothenusa * Math.sin(angle); // it's sin and not cos because of stupid Math.atan2 way of delivering angles
		p_adjusted[1][index] = p[1][index] + hypothenusa * Math.cos(angle);
	}
	/**Add a point either at the end or between two existing points, with accuracy depending on magnification. The width of the new point is that of the closest point after which it is inserted.*/
	protected int addPoint(int x_p, int y_p, double magnification, double bezier_finess, long layer_id) {
		if (-1 == n_points) setupForDisplay(); //reload
		//lookup closest interpolated point and then get the closest clicked point to it
		int index = findClosestPoint(x_p, y_p, magnification, bezier_finess);
		//check array size
		if (p[0].length == n_points) {
			enlargeArrays();
		}
		//decide:
		if (0 == n_points || 1 == n_points || index + 1 == n_points) {
			//append at the end
			p[0][n_points] = p_l[0][n_points] = p_r[0][n_points] = x_p;
			p[1][n_points] = p_l[1][n_points] = p_r[1][n_points] = y_p;
			p_layer[n_points] = layer_id;
			p_width[n_points] = (0 == n_points ? 1.0D : p_width[n_points -1]); // either 1.0 or the same as the last point
			index = n_points;
		} else if (-1 == index) {
			// decide whether to append at the end or prepend at the beginning
			// compute distance in the 3D space to the first and last points
			final double lz = layer_set.getLayer(layer_id).getZ();
			final double p0z =layer_set.getLayer(p_layer[0]).getZ();
			final double pNz =layer_set.getLayer(p_layer[n_points -1]).getZ();
			double sqdist0 =   (p[0][0] - x_p) * (p[0][0] - x_p)
				         + (p[1][0] - y_p) * (p[1][0] - y_p)
					 + (lz - p0z) * (lz - p0z);
			double sqdistN =   (p[0][n_points-1] - x_p) * (p[0][n_points-1] - x_p)
				         + (p[1][n_points-1] - y_p) * (p[1][n_points-1] - y_p)
					 + (lz - pNz) * (lz - pNz);
			if (sqdistN < sqdist0) {
				//append at the end
				p[0][n_points] = p_l[0][n_points] = p_r[0][n_points] = x_p;
				p[1][n_points] = p_l[1][n_points] = p_r[1][n_points] = y_p;
				p_layer[n_points] = layer_id;
				p_width[n_points] = p_width[n_points -1];
				index = n_points;
			} else {
				// prepend at the beginning
				for (int i=n_points-1; i>-1; i--) {
					p[0][i+1] = p[0][i];
					p[1][i+1] = p[1][i];
					p_l[0][i+1] = p_l[0][i];
					p_l[1][i+1] = p_l[1][i];
					p_r[0][i+1] = p_r[0][i];
					p_r[1][i+1] = p_r[1][i];
					p_width[i+1] = p_width[i];
					p_layer[i+1] = p_layer[i];
				}
				p[0][0] = p_l[0][0] = p_r[0][0] = x_p;
				p[1][0] = p_l[1][0] = p_r[1][0] = y_p;
				p_width[0] = p_width[1];
				p_layer[0] = layer_id;
				index = 0;
			}
			//debug: I miss gdb/ddd !
			//Utils.log("p_width.length = " + p_width.length + "  || n_points = " + n_points + " || p[0].length = " + p[0].length);
		} else {
			//insert at index:
			/*
			Utils.log("  p length = " + p[0].length
			      + "\np_l length = " + p_l[0].length
			      + "\np_r length = " + p_r[0].length
			      + "\np_layer len= " + p_layer.length);
			*/
			index++; //so it is added after the closest point;
			// 1 - copy second half of array
			int sh_length = n_points -index;
			double[][] p_copy = new double[2][sh_length];
			double[][] p_l_copy = new double[2][sh_length];
			double[][] p_r_copy = new double[2][sh_length];
			long[] p_layer_copy = new long[sh_length];
			double[] p_width_copy = new double[sh_length];
			System.arraycopy(p[0], index, p_copy[0], 0, sh_length);
			System.arraycopy(p[1], index, p_copy[1], 0, sh_length);
			System.arraycopy(p_l[0], index, p_l_copy[0], 0, sh_length);
			System.arraycopy(p_l[1], index, p_l_copy[1], 0, sh_length);
			System.arraycopy(p_r[0], index, p_r_copy[0], 0, sh_length);
			System.arraycopy(p_r[1], index, p_r_copy[1], 0, sh_length);
			//Utils.log2("index=" + index + "  sh_length=" + sh_length + "  p_layer.length=" + p_layer.length + "  p_layer_copy.length=" + p_layer_copy.length + "  p_copy[0].length=" + p_copy[0].length);
			System.arraycopy(p_layer, index, p_layer_copy, 0, sh_length);
			System.arraycopy(p_width, index, p_width_copy, 0, sh_length);
			// 2 - insert value into 'p' (the two control arrays get the same value)
			p[0][index] = p_l[0][index] = p_r[0][index] = x_p;
			p[1][index] = p_l[1][index] = p_r[1][index] = y_p;
			p_layer[index] = layer_id;
			p_width[index] = p_width[index-1]; // -1 because the index has been increased by 1 above
			// 3 - copy second half into the array
			System.arraycopy(p_copy[0], 0, p[0], index+1, sh_length);
			System.arraycopy(p_copy[1], 0, p[1], index+1, sh_length);
			System.arraycopy(p_l_copy[0], 0, p_l[0], index+1, sh_length);
			System.arraycopy(p_l_copy[1], 0, p_l[1], index+1, sh_length);
			System.arraycopy(p_r_copy[0], 0, p_r[0], index+1, sh_length);
			System.arraycopy(p_r_copy[1], 0, p_r[1], index+1, sh_length);
			System.arraycopy(p_layer_copy, 0, p_layer, index+1, sh_length);
			System.arraycopy(p_width_copy, 0, p_width, index+1, sh_length);
		}
		//add one up
		this.n_points++;

		return index;
	}
	/**Find the closest point to an interpolated point with precision depending upon magnification.*/
	protected int findClosestPoint(int x_p, int y_p, double magnification, double bezier_finess) {
		int index = -1;
		double distance_sq = Double.MAX_VALUE;
		double distance_sq_i;
		double max = 12.0D / magnification;
		max = max * max; //squaring it
		for (int i=0; i<p_i[0].length; i++) {
			//see which point is closer (there's no need to calculate the distance by multiplying squares and so on).
			distance_sq_i = (p_i[0][i] - x_p)*(p_i[0][i] - x_p) + (p_i[1][i] - y_p)*(p_i[1][i] - y_p);
			if (distance_sq_i < max && distance_sq_i < distance_sq) {
				index = i;
				distance_sq = distance_sq_i;
			}
		}
		if (-1 != index) {
			int index_found = (int)Math.round((double)index * bezier_finess);
			// correct to be always the one after: count the number of points before reaching the next point
			if (index < (index_found / bezier_finess)) {
				index_found--;
			}
			index = index_found;
			// allow only when the closest index is visible in the current layer
			// handled at mousePressed now//if (Display.getFrontLayer(this.project).getId() != p_layer[index]) return -1;
		}
		return index;
	}
	protected void generateInterpolatedPoints(double bezier_finess) {
		if (0 == n_points) {
			return;
		}

		int n = n_points;

		// case there's only one point
		if (1 == n_points) {
			p_i = new double[2][1];
			p_i[0][0] = p[0][0];
			p_i[1][0] = p[1][0];
			p_width_i = new double[1];
			p_width_i[0] = p_width[0];
			return;
		}
		// case there's more: interpolate!
		p_i = new double[2][(int)(n * (1.0D/bezier_finess))];
		p_width_i = new double[p_i[0].length];
		double t, f0, f1, f2, f3;
		int next = 0;
		for (int i=0; i<n-1; i++) {
			for (t=0.0D; t<1.0D; t += bezier_finess) {
				f0 = (1-t)*(1-t)*(1-t);
				f1 = 3*t*(1-t)*(1-t);
				f2 = 3*t*t*(1-t);
				f3 = t*t*t;
				p_i[0][next] = f0*p[0][i] + f1*p_r[0][i] + f2*p_l[0][i+1] + f3*p[0][i+1];
				p_i[1][next] = f0*p[1][i] + f1*p_r[1][i] + f2*p_l[1][i+1] + f3*p[1][i+1];
				p_width_i[next] = p_width[i]*(1-t) + p_width[i+1]*t;
				next++;
				//enlarge if needed (when bezier_finess is not 0.05, it's difficult to predict because of int loss of precision.
				if (p_i[0].length == next) {
					double[][] p_i_copy = new double[2][p_i[0].length + 5];
					double[] p_width_i_copy = new double[p_width_i.length + 5];
					System.arraycopy(p_i[0], 0, p_i_copy[0], 0, p_i[0].length);
					System.arraycopy(p_i[1], 0, p_i_copy[1], 0, p_i[1].length);
					System.arraycopy(p_width_i, 0, p_width_i_copy, 0, p_width_i.length);
					p_i = p_i_copy;
					p_width_i = p_width_i_copy;
				}
			}
		}
		// add the last point
		if (p_i[0].length == next) {
			double[][] p_i_copy = new double[2][p_i[0].length + 1];
			double[] p_width_i_copy = new double[p_width_i.length + 1];
			System.arraycopy(p_i[0], 0, p_i_copy[0], 0, p_i[0].length);
			System.arraycopy(p_i[1], 0, p_i_copy[1], 0, p_i[1].length);
			System.arraycopy(p_width_i, 0, p_width_i_copy, 0, p_width_i.length);
			p_i = p_i_copy;
			p_width_i = p_width_i_copy;
		}
		p_i[0][next] = p[0][n_points-1];
		p_i[1][next] = p[1][n_points-1];
		p_width_i[next] = p_width[n_points-1];
		next++;

		if (p_i[0].length != next) { // 'next' works as a length here
			//resize back
			double[][] p_i_copy = new double[2][next];
			double[] p_width_i_copy = new double[next];
			System.arraycopy(p_i[0], 0, p_i_copy[0], 0, next);
			System.arraycopy(p_i[1], 0, p_i_copy[1], 0, next);
			System.arraycopy(p_width_i, 0, p_width_i_copy, 0, next);
			p_i = p_i_copy;
			p_width_i = p_width_i_copy;
		}
	}

	public void paint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (0 == n_points) return;
		if (-1 == n_points) {
			// load points from the database
			setupForDisplay();
		}
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[][] p_i = this.p_i;
		double[] p_width = this.p_width;
		double[] p_width_i = this.p_width_i;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_i = (double[][])ob[3];
			p_width = (double[])ob[4];
			p_width_i = (double[])ob[5];
		}

		if (active) {
			final long layer_id = active_layer.getId();
			// draw/fill points
			final int oval_radius = (int)Math.ceil(4 / magnification);
			final int oval_corr = (int)Math.ceil(3 / magnification);
			for (int j=0; j<n_points; j++) { //TODO there is room for optimization, operations are being done twice or 3 times; BUT is the creation of new variables as costly as the calculations? I have no idea.
				if (layer_id != p_layer[j]) continue;
				//draw big ovals at backbone points
				DisplayCanvas.drawHandle(g, (int)p[0][j], (int)p[1][j], magnification);
				g.setColor(color);
				//fill small ovals at control points
				g.fillOval((int)p_l[0][j] -oval_corr, (int)p_l[1][j] -oval_corr, oval_radius, oval_radius);
				g.fillOval((int)p_r[0][j] -oval_corr, (int)p_r[1][j] -oval_corr, oval_radius, oval_radius);
				//draw lines between backbone and control points
				g.drawLine((int)p[0][j], (int)p[1][j], (int)p_l[0][j], (int)p_l[1][j]);
				g.drawLine((int)p[0][j], (int)p[1][j], (int)p_r[0][j], (int)p_r[1][j]);
			}
		}
		// paint the tube in 2D:
		if (n_points > 1 && p_i[0].length > 1) { // need the second check for repaints that happen before generating the interpolated points.
			double angle = 0;
			double a0 = Math.toRadians(0);
			double a90 = Math.toRadians(90);
			double a180 = Math.toRadians(180);
			double a270 = Math.toRadians(270);
			int n = p_i[0].length;
			double[] r_side_x = new double[n];
			double[] r_side_y = new double[n];
			double[] l_side_x = new double[n];
			double[] l_side_y = new double[n];
			int m = n-1;

			for (int i=0; i<n-1; i++) {
				angle = Math.atan2(p_i[0][i+1] - p_i[0][i], p_i[1][i+1] - p_i[1][i]);

				r_side_x[i] = p_i[0][i] + Math.sin(angle+a90) * p_width_i[i];  //sin and cos are inverted, but works better like this. WHY ??
				r_side_y[i] = p_i[1][i] + Math.cos(angle+a90) * p_width_i[i];
				l_side_x[i] = p_i[0][i] + Math.sin(angle-a90) * p_width_i[i];
				l_side_y[i] = p_i[1][i] + Math.cos(angle-a90) * p_width_i[i];
			}

			angle = Math.atan2(p_i[0][m] - p_i[0][m-1], p_i[1][m] - p_i[1][m-1]);

			r_side_x[m] = p_i[0][m] + Math.sin(angle+a90) * p_width_i[m];
			r_side_y[m] = p_i[1][m] + Math.cos(angle+a90) * p_width_i[m];
			l_side_x[m] = p_i[0][m] + Math.sin(angle-a90) * p_width_i[m];
			l_side_y[m] = p_i[1][m] + Math.cos(angle-a90) * p_width_i[m];

			double z_current = active_layer.getZ();

			for (int j=0; j<n_points; j++) { // at least looping through 2 points, as guaranteed by the preconditions checking
				double z = layer_set.getLayer(p_layer[j]).getZ();
				if (z < z_current) g.setColor(Color.red);
				else if (z == z_current) g.setColor(this.color);
				else g.setColor(Color.blue);

				int fi = 0;
				int la = j * 20 -1;
				if (0 != j) fi = (j * 20) - 10; // 10 is half a segment
				if (n_points -1 != j) la += 10; // same //= j * 20 + 9;
				if (la +1 >= r_side_x.length) la--; // quick fix
				if (fi > la) fi = la;

				for (int k=fi; k<=la; k++) {
					g.drawLine((int)r_side_x[k], (int)r_side_y[k], (int)r_side_x[k+1], (int)r_side_y[k+1]);
					g.drawLine((int)l_side_x[k], (int)l_side_y[k], (int)l_side_x[k+1], (int)l_side_y[k+1]);
				}
			}
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	public void keyPressed(KeyEvent ke) {
		//Utils.log2("Pipe.keyPressed not implemented.");
	}

	/**Helper vars for mouse events. It's safe to have them static since only one Pipe will be edited at a time.*/
	static private int index, index_l, index_r;
	static private boolean is_new_point = false;

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {

			if (me.isControlDown() && me.isShiftDown()) {
				index = findNearestPoint(p, n_points, x_p, y_p);
			} else {
				index = findPoint(p, x_p, y_p, mag);
			}

			if (-1 != index) {
				if (me.isControlDown() && me.isShiftDown() && p_layer[index] == Display.getFrontLayer(this.project).getId()) {
					//delete point
					removePoint(index);
					index = index_r = index_l = -1;
					repaint();
					return;
				} else if (me.isAltDown()) {
					resetControlPoints(index);
					return;
				}
			}

			// find if click is on a left control point
			index_l = findPoint(p_l, x_p, y_p, mag);
			index_r = -1;
			// if not, then try on the set of right control points
			if (-1 == index_l) {
				index_r = findPoint(p_r, x_p, y_p, mag);
			}

			long layer_id = Display.getFrontLayer(this.project).getId();

			if (-1 != index && layer_id != p_layer[index]) index = -1; // disable!
			else if (-1 != index_l && layer_id != p_layer[index_l]) index_l = -1;
			else if (-1 != index_r && layer_id != p_layer[index_r]) index_r = -1;
			//if no conditions are met, attempt to add point
			else if (-1 == index && -1 == index_l && -1 == index_r && !me.isShiftDown() && !me.isAltDown()) {
				//add a new point and assign its index to the left control point, so it can be dragged right away. This is copying the way Karbon does for drawing Bezier curves, which is the contrary to Adobe's way, but which I find more useful.
				index_l = addPoint(x_p, y_p, mag, 0.05, layer_id);
				is_new_point = true;
				if (0 == index_l) { //1 == n_points)
					//for the very first point, drag the right control point, not the left.
					index_r = index_l;
					index_l = -1;
				} else {
					generateInterpolatedPoints(0.05);
				}
				repaint();
				return;
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double p = inverseTransformPoint(x_p, y_p);
			x_p = (int)p.x;
			y_p = (int)p.y;
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			//if a point in the backbone is found, then:
			if (-1 != index) {
				if (!me.isAltDown() && !me.isShiftDown()) {
					dragPoint(index, x_d - x_d_old, y_d - y_d_old);
				} else if (me.isShiftDown()) {
					// resize radius
					p_width[index] = Math.sqrt((x_d - p[0][index])*(x_d - p[0][index]) + (y_d - p[1][index])*(y_d - p[1][index]));
					Utils.showStatus("radius: " + p_width[index], false);
				} else { //TODO in linux the alt+click is stolen by the KDE window manager but then the middle-click works as if it was the alt+click. Weird!
					//drag both control points symmetrically
					dragControlPoint(index, x_d, y_d, p_l, p_r, true);
				}
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}

			//if a control point is found, then drag it, adjusting the other control point non-symmetrically
			if (-1 != index_r) {
				dragControlPoint(index_r, x_d, y_d, p_r, p_l, is_new_point); //((index_r != n_points-1)?false:true)); //last point gets its 2 control points dragged symetrically
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}
			if (-1 != index_l) {
				dragControlPoint(index_l, x_d, y_d, p_l, p_r, is_new_point); //((index_l != n_points-1)?false:true)); //last point gets its 2 control points dragged symetrically
				generateInterpolatedPoints(0.05);
				repaint();
				return;
			}
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			//generate interpolated points
			generateInterpolatedPoints(0.05);
			repaint(); //needed at least for the removePoint
		}

		//update points in database if there was any change
		if (-1 != index || -1 != index_r || -1 != index_l) {
			//updateInDatabase("position");
			if (is_new_point) {
				// update all points, since the index may have changed
				updateInDatabase("points");
			} else if (-1 != index && index != n_points) { //second condition happens when thelast point has been removed
				updateInDatabase(getUpdatePointForSQL(index));
			} else if (-1 != index_r) {
				updateInDatabase(getUpdateRightControlPointForSQL(index_r));
			} else if (-1 != index_l) {
				updateInDatabase(getUpdateLeftControlPointForSQL(index_l));
			} else if (index != n_points) { // don't do it when the last point is removed
				// update all
				updateInDatabase("points");
			}
			updateInDatabase("dimensions");
		} else if (x_r != x_p || y_r != y_p) {
			updateInDatabase("dimensions");
		}

		Display.repaint(layer, this, 5); // the entire Displayable object, snapshot included.

		// reset
		is_new_point = false;
		index = index_r = index_l = -1;
	}

	protected void calculateBoundingBox(final boolean adjust_position) {
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;
		if (0 == n_points) {
			this.width = this.height = 0;
			return;
		}
		// get perimeter of the tube
		Polygon pol = getPerimeter();
		if (0 != pol.npoints) {
			// check the tube
			for (int i=0; i<pol.npoints; i++) {
				if (pol.xpoints[i] < min_x) min_x = pol.xpoints[i];
				if (pol.ypoints[i] < min_y) min_y = pol.ypoints[i];
				if (pol.xpoints[i] > max_x) max_x = pol.xpoints[i];
				if (pol.ypoints[i] > max_y) max_y = pol.ypoints[i];
			}
		}
		// check the control points
		for (int i=0; i<n_points; i++) {
			if (p_l[0][i] < min_x) min_x = p_l[0][i];
			if (p_r[0][i] < min_x) min_x = p_r[0][i];
			if (p_l[1][i] < min_y) min_y = p_l[1][i];
			if (p_r[1][i] < min_y) min_y = p_r[1][i];
			if (p_l[0][i] > max_x) max_x = p_l[0][i];
			if (p_r[0][i] > max_x) max_x = p_r[0][i];
			if (p_l[1][i] > max_y) max_y = p_l[1][i];
			if (p_r[1][i] > max_y) max_y = p_r[1][i];
		}

		this.width = max_x - min_x;
		this.height = max_y - min_y;

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			for (int i=0; i<n_points; i++) {
				p[0][i] -= min_x;	p[1][i] -= min_y;
				p_l[0][i] -= min_x;	p_l[1][i] -= min_y;
				p_r[0][i] -= min_x;	p_r[1][i] -= min_y;
			}
			for (int i=0; i<p_i[0].length; i++) {
				p_i[0][i] -= min_x;	p_i[1][i] -= min_y;
			}
			this.at.translate(min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");
	}

	/**Release all memory resources taken by this object.*/
	public void destroy() {
		super.destroy();
		p = null;
		p_l = null;
		p_r = null;
		p_layer = null;
		p_width = null;
		p_i = null;
		p_width_i = null;
	}


	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Profile. */
	public void repaint() {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5);
	}

	/**Make this object ready to be painted.*/
	private void setupForDisplay() {
		// load points
		if (null == p || null == p_l || null == p_r) {
			ArrayList al = project.getLoader().fetchPipePoints(id);
			n_points = al.size();
			p = new double[2][n_points];
			p_l = new double[2][n_points];
			p_r = new double[2][n_points];
			p_layer = new long[n_points];
			p_width = new double[n_points];
			Iterator it = al.iterator();
			int i = 0;
			while (it.hasNext()) {
				Object[] ob = (Object[])it.next();
				p[0][i] = ((Double)ob[0]).doubleValue();
				p[1][i] = ((Double)ob[1]).doubleValue();
				p_r[0][i] = ((Double)ob[2]).doubleValue();
				p_r[1][i] = ((Double)ob[3]).doubleValue();
				p_l[0][i] = ((Double)ob[4]).doubleValue();
				p_l[1][i] = ((Double)ob[5]).doubleValue();
				p_width[i] = ((Double)ob[6]).doubleValue();
				p_layer[i] = ((Long)ob[7]).longValue();
				i++;
			}
			// recreate interpolated points
			generateInterpolatedPoints(0.05); //TODO adjust this or make it read the value from the Project perhaps.
		}
	}
	/**Release memory resources used by this object: namely the arrays of points, which can be reloaded with a call to setupForDisplay()*/
	public void flush() {
		p = null;
		p_l = null;
		p_r = null;
		p_i = null;
		p_width = null;
		p_layer = null;
		n_points = -1; // flag that points exist but are not loaded
	}

	/** The exact perimeter of this profile, in integer precision. */
	public Polygon getPerimeter() {
		if (null == p_i || p_i[0].length < 2) return new Polygon();  // meaning: if there aren't any interpolated points

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[][] p_i = this.p_i;
		double[] p_width = this.p_width;
		double[] p_width_i = this.p_width_i;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_i = (double[][])ob[3];
			p_width = (double[])ob[4];
			p_width_i = (double[])ob[5];
		}

		double angle = 0;
		final double a0 = Math.toRadians(0);
		final double a90 = Math.toRadians(90);
		final double a180 = Math.toRadians(180);
		final double a270 = Math.toRadians(270);
		int n = p_i[0].length; // the number of interpolated points
		double[] r_side_x = new double[n];
		double[] r_side_y = new double[n];
		double[] l_side_x = new double[n];
		double[] l_side_y = new double[n];
		int m = n-1;

		for (int i=0; i<n-1; i++) {
			angle = Math.atan2(p_i[0][i+1] - p_i[0][i], p_i[1][i+1] - p_i[1][i]);

			r_side_x[i] = p_i[0][i] + Math.sin(angle+a90) * p_width_i[i];  //sin and cos are inverted, but works better like this. WHY ??
			r_side_y[i] = p_i[1][i] + Math.cos(angle+a90) * p_width_i[i];
			l_side_x[i] = p_i[0][i] + Math.sin(angle-a90) * p_width_i[i];
			l_side_y[i] = p_i[1][i] + Math.cos(angle-a90) * p_width_i[i];
		}

		// last point
		angle = Math.atan2(p_i[0][m] - p_i[0][m-1], p_i[1][m] - p_i[1][m-1]);

		r_side_x[m] = p_i[0][m] + Math.sin(angle+a90) * p_width_i[m];
		r_side_y[m] = p_i[1][m] + Math.cos(angle+a90) * p_width_i[m];
		l_side_x[m] = p_i[0][m] + Math.sin(angle-a90) * p_width_i[m];
		l_side_y[m] = p_i[1][m] + Math.cos(angle-a90) * p_width_i[m];

		int[] pol_x = new int[n * 2];
		int[] pol_y = new int[n * 2];
		for (int j=0; j<n; j++) {
			pol_x[j] = (int)r_side_x[j];
			pol_y[j] = (int)r_side_y[j];
			pol_x[n + j] = (int)l_side_x[m -j];
			pol_y[n + j] = (int)l_side_y[m -j];
		}
		return new Polygon(pol_x, pol_y, pol_x.length);
	}

	/** Writes the data of this object as a Pipe object in the .shapes file represented by the 'data' StringBuffer. */
	public void toShapesFile(StringBuffer data, String group, String color, double z_scale) {
		if (-1 == n_points) setupForDisplay(); // reload
		final char l = '\n';
		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[] p_width = this.p_width;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_width = (double[])ob[4];
		}
		data.append("type=pipe").append(l)
		    .append("name=").append(project.getMeaningfulTitle(this)).append(l)
		    .append("group=").append(group).append(l)
		    .append("color=").append(color).append(l)
		    .append("supergroup=").append("null").append(l)
		    .append("supercolor=").append("null").append(l)
		;
		for (int i=0; i<n_points; i++) {
			data.append("p x=").append(p[0][i]).append(l)
			    .append("p y=").append(p[1][i]).append(l)
			    .append("p_r x=").append(p_r[0][i]).append(l)
			    .append("p_r y=").append(p_r[1][i]).append(l)
			    .append("p_l x=").append(p_l[0][i]).append(l)
			    .append("p_l y=").append(p_l[1][i]).append(l)
			    .append("z=").append(layer_set.getLayer(p_layer[i]).getZ() * z_scale).append(l)
			    .append("width=").append(p_width[i]).append(l)
			;
		}
	}

	/** Return the list of query statements needed to insert all the points in the database. */
	public String[] getPointsForSQL() {
		String[] sql = new String[n_points];
		for (int i=0; i<n_points; i++) {
			StringBuffer sb = new StringBuffer("INSERT INTO ab_pipe_points (pipe_id, index, x, y, x_r, y_r, x_l, y_l, width, layer_id) VALUES (");
			sb.append(this.id).append(",")
			  .append(i).append(",")
			  .append(p[0][i]).append(",")
			  .append(p[1][i]).append(",")
			  .append(p_r[0][i]).append(",")
			  .append(p_r[1][i]).append(",")
			  .append(p_l[0][i]).append(",")
			  .append(p_l[1][i]).append(",")
			  .append(p_width[i]).append(",")
			  .append(p_layer[i])
			  .append(")");
			; //end
			sql[i] = sb.toString();
		}
		return sql;
	}

	public String getUpdatePointForSQL(int index) {
		if (index < 0 || index > n_points-1) return null;

		StringBuffer sb = new StringBuffer("UPDATE ab_pipe_points SET ");
		sb.append("x=").append(p[0][index])
		  .append(", y=").append(p[1][index])
		  .append(", x_r=").append(p_r[0][index])
		  .append(", y_r=").append(p_r[1][index])
		  .append(", x_l=").append(p_l[0][index])
		  .append(", y_l=").append(p_l[1][index])
		  .append(", width=").append(p_width[index])
		  .append(", layer_id=").append(p_layer[index])
		  .append(" WHERE pipe_id=").append(this.id)
		  .append(" AND index=").append(index)
		; //end
		return sb.toString();
	}

	String getUpdateLeftControlPointForSQL(int index) {
		if (index < 0 || index > n_points-1) return null;

		StringBuffer sb = new StringBuffer("UPDATE ab_pipe_points SET ");
		sb.append("x_l=").append(p_l[0][index])
		  .append(", y_l=").append(p_l[1][index])
		  .append(" WHERE pipe_id=").append(this.id)
		  .append(" AND index=").append(index)
		; //end
		return sb.toString();
	}

	String getUpdateRightControlPointForSQL(int index) {
		if (index < 0 || index > n_points-1) return null;

		StringBuffer sb = new StringBuffer("UPDATE ab_pipe_points SET ");
		sb.append("x_r=").append(p_r[0][index])
		  .append(", y_r=").append(p_r[1][index])
		  .append(" WHERE pipe_id=").append(this.id)
		  .append(" AND index=").append(index)
		; //end
		return sb.toString();
	}

	public boolean isDeletable() {
		return 0 == n_points;
	}

	/** Test whether the Pipe contains the given point at the given layer. What it does: generates subpolygons that are present in the given layer, and tests whether the point is contained in any of them. */
	public boolean contains(final Layer layer, int x, int y) {
		if (-1 == n_points) setupForDisplay(); // reload points
		if (0 == n_points) return false;
		// make x,y local
		final Point2D.Double po = inverseTransformPoint(x, y);
		x = (int)po.x;
		y = (int)po.y;
		if (1 == n_points) {
			if (Math.abs(p[0][0] - x) < 3 && Math.abs(p[1][0] - y) < 3) return true; // error in clicked precision of 3 pixels
			else return false;
		}
		double angle = 0;
		double a0 = Math.toRadians(0);
		double a90 = Math.toRadians(90);
		double a180 = Math.toRadians(180);
		double a270 = Math.toRadians(270);
		int n = p_i[0].length; // the number of interpolated points
		double[] r_side_x = new double[n];
		double[] r_side_y = new double[n];
		double[] l_side_x = new double[n];
		double[] l_side_y = new double[n];
		int m = n-1;

		for (int i=0; i<n-1; i++) {
			angle = Math.atan2(p_i[0][i+1] - p_i[0][i], p_i[1][i+1] - p_i[1][i]);

			// side points, displaced by this.x, this.y
			r_side_x[i] = p_i[0][i] + Math.sin(angle+a90) * p_width_i[i];  //sin and cos are inverted, but works better like this. WHY ??
			r_side_y[i] = p_i[1][i] + Math.cos(angle+a90) * p_width_i[i];
			l_side_x[i] = p_i[0][i] + Math.sin(angle-a90) * p_width_i[i];
			l_side_y[i] = p_i[1][i] + Math.cos(angle-a90) * p_width_i[i];
		}

		// last point
		angle = Math.atan2(p_i[0][m] - p_i[0][m-1], p_i[1][m] - p_i[1][m-1]);

		// side points, displaced by this.x, this.y
		r_side_x[m] = p_i[0][m] + Math.sin(angle+a90) * p_width_i[m];
		r_side_y[m] = p_i[1][m] + Math.cos(angle+a90) * p_width_i[m];
		l_side_x[m] = p_i[0][m] + Math.sin(angle-a90) * p_width_i[m];
		l_side_y[m] = p_i[1][m] + Math.cos(angle-a90) * p_width_i[m];

		final long layer_id = layer.getId();
		//double z_given = layer.getZ();
		//double z = 0;
		int first = 0; // the first backbone point in the subpolygon present in the layer
		int last = 0; // the last backbone point in the subpolygon present in the layer

		boolean add_pol = false;

		for (int j=0; j<n_points; j++) { // at least looping through 2 points, as guaratneed by the preconditions checking
			if (layer_id != p_layer[j]) {
				first = j + 1; // perhaps next, not this j
				continue;
			}
			last = j;
			// if j is last point, or the next point won't be in the same layer:
			if (j == n_points -1 || layer_id != p_layer[j+1]) {
				add_pol = true;
			}
			if (add_pol) {
				// compute sub polygon
				int fi = 0;
				int la = last * 20 -1;
				if (0 != first) fi = (first * 20) - 10; // 10 is half a segment
				if (n_points -1 != last) la += 10; // same //= last * 20 + 9;
				int length = la - fi + 1; // +1 because fi and la are indexes

				int [] pol_x = new int[length * 2];
				int [] pol_y = new int[length * 2];
				for (int k=0, g=fi; g<=la; g++, k++) {
					pol_x[k] = (int)r_side_x[g];
					pol_y[k] = (int)r_side_y[g];
					pol_x[length + k] = (int)l_side_x[la - k];
					pol_y[length + k] = (int)l_side_y[la - k];
				}
				Polygon pol = new Polygon(pol_x, pol_y, pol_x.length);
				if (pol.contains(x, y)) return true;
				// reset
				first = j + 1;
				add_pol = false;
			}
		}
		return false;
	}

	/** Get the perimeter of all parts that show in the given layer (as defined by its Z). Returns null if none found. */
	private Polygon[] getSubPerimeters(final Layer layer) {
		if (n_points <= 1) return null;

		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[][] p_i = this.p_i;
		double[] p_width = this.p_width;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_i = (double[][])ob[3];
			p_width = (double[])ob[4];
			p_width_i = (double[])ob[5];
		}

		double angle = 0;
		double a0 = Math.toRadians(0);
		double a90 = Math.toRadians(90);
		double a180 = Math.toRadians(180);
		double a270 = Math.toRadians(270);
		int n = p_i[0].length; // the number of interpolated points
		double[] r_side_x = new double[n];
		double[] r_side_y = new double[n];
		double[] l_side_x = new double[n];
		double[] l_side_y = new double[n];
		int m = n-1;

		for (int i=0; i<n-1; i++) {
			angle = Math.atan2(p_i[0][i+1] - p_i[0][i], p_i[1][i+1] - p_i[1][i]);

			// side points
			r_side_x[i] = p_i[0][i] + Math.sin(angle+a90) * p_width_i[i];  //sin and cos are inverted, but works better like this. WHY ??
			r_side_y[i] = p_i[1][i] + Math.cos(angle+a90) * p_width_i[i];
			l_side_x[i] = p_i[0][i] + Math.sin(angle-a90) * p_width_i[i];
			l_side_y[i] = p_i[1][i] + Math.cos(angle-a90) * p_width_i[i];
		}

		// last point
		angle = Math.atan2(p_i[0][m] - p_i[0][m-1], p_i[1][m] - p_i[1][m-1]);

		// side points, displaced by this.x, this.y
		r_side_x[m] = p_i[0][m] + Math.sin(angle+a90) * p_width_i[m];
		r_side_y[m] = p_i[1][m] + Math.cos(angle+a90) * p_width_i[m];
		l_side_x[m] = p_i[0][m] + Math.sin(angle-a90) * p_width_i[m];
		l_side_y[m] = p_i[1][m] + Math.cos(angle-a90) * p_width_i[m];

		final long layer_id = layer.getId();
		//double z_given = layer.getZ();
		//double z = 0;
		int first = 0; // the first backbone point in the subpolygon present in the layer
		int last = 0; // the last backbone point in the subpolygon present in the layer

		boolean add_pol = false;
		final ArrayList al = new ArrayList();

		for (int j=0; j<n_points; j++) {
			if (layer_id != p_layer[j]) {
				first = j + 1; // perhaps next, not this j
				continue;
			}
			last = j;
			// if j is last point, or the next point won't be in the same layer:
			if (j == n_points -1 || layer_id != p_layer[j+1]) {
				add_pol = true;
			}
			if (add_pol) {
				// compute sub polygon
				int fi = 0;
				int la = last * 20 -1;
				if (0 != first) fi = (first * 20) - 10; // 10 is half a segment
				if (n_points -1 != last) la += 10; // same //= last * 20 + 9;
				int length = la - fi + 1; // +1 because fi and la are indexes

				int [] pol_x = new int[length * 2];
				int [] pol_y = new int[length * 2];
				for (int k=0, g=fi; g<=la; g++, k++) {
					pol_x[k] = (int)r_side_x[g];
					pol_y[k] = (int)r_side_y[g];
					pol_x[length + k] = (int)l_side_x[la - k];
					pol_y[length + k] = (int)l_side_y[la - k];
				}
				al.add(new Polygon(pol_x, pol_y, pol_x.length));
				// reset
				first = j + 1;
				add_pol = false;
			}
		}
		if (al.isEmpty()) return null;
		else {
			final Polygon[] pols = new Polygon[al.size()];
			al.toArray(pols);
			return pols;
		}
	}

	// scan the Display and link Patch objects that lay under this Pipe's bounding box:
	public void linkPatches() { // TODO needs to check all layers!!
		// SHOULD check on every layer where there is a subperimeter. This method below will work only while the Display is not switching layer before deselecting this pipe (which calls linkPatches)

		// find the patches that don't lay under other profiles of this profile's linking group, and make sure they are unlinked. This will unlink any Patch objects under this Pipe:
		unlinkAll(Patch.class);

		HashSet hs = new HashSet();
		for (int l=0; l<n_points; l++) {
			// avoid repeating the ones that have been done
			Long lo = new Long(p_layer[l]); // in blankets ...
			if (hs.contains(lo)) continue;
			else hs.add(lo);

			Layer layer = layer_set.getLayer(p_layer[l]);

			// this bounding box as in the current layer
			final Polygon[] perimeters = getSubPerimeters(layer);
			if (null == perimeters) continue;

			// catch all displayables of the current Layer
			final ArrayList al = layer.getDisplayables(Patch.class);

			// for each Patch, check if it underlays this profile's bounding box
			final Rectangle box = new Rectangle();
			for (Iterator itd = al.iterator(); itd.hasNext(); ) {
				final Displayable displ = (Displayable)itd.next();
				// stupid java, Polygon cannot test for intersection with another Polygon !! //if (perimeter.intersects(displ.getPerimeter())) // TODO do it yourself: check if a Displayable intersects another Displayable
				for (int i=0; i<perimeters.length; i++) {
					if (perimeters[i].intersects(displ.getBoundingBox(box))) {
						// Link the patch
						this.link(displ);
					}
				}
			}
		}
	}

	/** Returns the layer of lowest Z coordinate where this ZDisplayable has a point in, or the creation layer if no points yet. */
	public Layer getFirstLayer() {
		if (0 == n_points) return this.layer;
		if (-1 == n_points) setupForDisplay(); //reload
		Layer la = this.layer;
		double z = Double.MAX_VALUE;
		for (int i=0; i<n_points; i++) {
			Layer layer = layer_set.getLayer(p_layer[i]);
			if (layer.getZ() < z) la = layer;
		}
		return la;
	}

	public void exportSVG(StringBuffer data, double z_scale, String indent) {
		String in = indent + "\t";
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		final double[] a = new double[6];
		at.getMatrix(a);
		data.append(indent).append("<path\n")
		    .append(in).append("type=\"pipe\"\n")
		    .append(in).append("id=\"").append(id).append("\"\n")
		    .append(in).append("transform=\"matrix(").append(a[0]).append(',')
								.append(a[1]).append(',')
								.append(a[2]).append(',')
								.append(a[3]).append(',')
								.append(a[4]).append(',')
								.append(a[5]).append(")\"\n")
		    .append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n")
		    .append(in).append("d=\"M")
		;
		for (int i=0; i<n_points-1; i++) {
			data.append(" ").append(p[0][i]).append(",").append(p[1][i])
			    .append(" C ").append(p_r[0][i]).append(",").append(p_r[1][i])
			    .append(" ").append(p_l[0][i+1]).append(",").append(p_l[1][i+1])
			;
		}
		data.append(" ").append(p[0][n_points-1]).append(',').append(p[1][n_points-1]);
		data.append("\"\n")
		    .append(in).append("z=\"")
		;
		for (int i=0; i<n_points; i++) {
			data.append(layer_set.getLayer(p_layer[i]).getZ() * z_scale).append(",");
		}
		data.append(in).append("\"\n");
		data.append(in).append("p_width=\"");
		for (int i=0; i<n_points; i++) {
			data.append(p_width[i]).append(",");
		}
		data.append("\"\n")
		    .append(in).append("links=\"");
		if (null != hs_linked && 0 != hs_linked.size()) {
			int ii = 0;
			int len = hs_linked.size();
			for (Iterator it = hs_linked.iterator(); it.hasNext(); ) {
				Object ob = it.next();
				data.append(((DBObject)ob).getId());
				if (ii != len-1) data.append(",");
				ii++;
			}
		}
		data.append(indent).append("\"\n/>\n");
	}

	/** Returns a [p_i[0].length][4] array, with x,y,z,radius on the second part. Not translated to x,y but local!*/
	public double[][] getBackbone() {
		if (-1 == n_points) setupForDisplay(); // reload
		double[][] b = new double[p_i[0].length][4];
		int ni = 20; //  1/0.05;
		int start = 0;
		for (int j=0; j<n_points -1; j++) {
			double z1 = layer_set.getLayer(p_layer[j]).getZ();
			double z2 = layer_set.getLayer(p_layer[j+1]).getZ();
			double depth = z2 - z1;
			double radius1 = p_width[j];
			double radius2 = p_width[j+1];
			double dif = radius2 - radius1;
			for (int i=start, k=0; i<start + ni; i++, k++) {
				b[i][0] = p_i[0][i];
				b[i][1] = p_i[1][i];
				b[i][2] = z1 + (k * depth) / (double)ni;
				b[i][3] = radius1 + (k * dif) / (double)ni;
			}
			start += ni;
		}
		// last point
		start = p_i[0].length-1; // recycling start
		b[start][0] = p[0][n_points-1];
		b[start][1] = p[1][n_points-1];
		b[start][2] = layer_set.getLayer(p_layer[n_points-1]).getZ();
		b[start][3] = p_width[n_points-1];
		return b;
	}

	public void rotateData(int direction) {
		boolean flushed = false;
		if (-1 == n_points) {
			setupForDisplay(); //reload
			flushed = true;
		}
		if (0 == n_points) return;
		double tmp;
		for (int i=0; i<n_points; i++) {
			switch (direction) {
				case LayerSet.R90:
					tmp = p[0][i];
					p[0][i] = height - p[1][i]; 
					p[1][i] = tmp;
					tmp = p_r[0][i];
					p_r[0][i] = height - p_r[1][i];
					p_r[1][i] = tmp;
					tmp = p_l[0][i];
					p_l[0][i] = height - p_l[1][i];
					p_l[1][i] = tmp;
					break;
				case LayerSet.R270:
					tmp = p[0][i];
					p[0][i] = p[1][i]; 
					p[1][i] = width - tmp;
					tmp = p_r[0][i];
					p_r[0][i] = p_r[1][i];
					p_r[1][i] = width - tmp;
					tmp = p_l[0][i];
					p_l[0][i] = p_l[1][i];
					p_l[1][i] = width - tmp;
					break;
				case LayerSet.FLIP_HORIZONTAL:
					p[0][i] = width - p[0][i];
					p_r[0][i] = width - p_r[0][i];
					p_l[0][i] = width - p_l[0][i];
					break;
				case LayerSet.FLIP_VERTICAL:
					p[1][i] = height - p[1][i];
					p_r[1][i] = height - p_r[1][i];
					p_l[1][i] = height - p_l[1][i];
					break;
			}
		}
		updateInDatabase("points");
		// restore loaded state
		if (flushed) flush();
	}

	/** x,y is the cursor position in offscreen coordinates. */
	public void snapTo(int cx, int cy, int x_p, int y_p) { // WARNING: DisplayCanvas is locking at mouseDragged when the cursor is outside the DisplayCanvas Component, so this is useless or even harmful at the moment. 
		if (-1 != index) {
			// #$#@$%#$%!!! TODO this doesn't work, although it *should*. The index_l and index_r work, and the mouseEntered coordinates are fine too. Plus it messes up the x,y position or something, for then on reload the pipe is streched or displaced (not clear).
			/*
			double dx = p_l[0][index] - p[0][index];
			double dy = p_l[1][index] - p[1][index];
			p_l[0][index] = cx + dx;
			p_l[1][index] = cy + dy;
			dx = p_r[0][index] - p[0][index];
			dy = p_r[1][index] - p[1][index];
			p_r[0][index] = cx + dx;
			p_r[1][index] = cy + dy;
			p[0][index] = cx;
			p[1][index] = cy;
			*/
		} else if (-1 != index_l) {
			p_l[0][index_l] = cx;
			p_l[1][index_l] = cy;
		} else if (-1 != index_r) {
			p_r[0][index_r] = cx;
			p_r[1][index_r] = cy;
		} else {
			// drag the whole pipe
			// CONCEPTUALLY WRONG, what happens when not dragging the pipe, on mouseEntered? Disaster!
			//drag(cx - x_p, cy - y_p);
		}
	}

	/** Exports data, the tag is not opened nor closed. */
	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_pipe\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		if (-1 == n_points) setupForDisplay(); // reload
		if (0 == n_points) return;
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n")
		       .append(in).append("d=\"M")
		;
		for (int i=0; i<n_points-1; i++) {
			sb_body.append(" ").append(p[0][i]).append(",").append(p[1][i])
			    .append(" C ").append(p_r[0][i]).append(",").append(p_r[1][i])
			    .append(" ").append(p_l[0][i+1]).append(",").append(p_l[1][i+1])
			;
		}
		sb_body.append(" ").append(p[0][n_points-1]).append(',').append(p[1][n_points-1]);
		sb_body.append("\"\n")
		    .append(in).append("layer_ids=\"") // different from 'layer_id' in superclass
		;
		for (int i=0; i<n_points; i++) {
			sb_body.append(p_layer[i]);
			if (n_points -1 != i) sb_body.append(",");
		}
		sb_body.append("\"\n");
		sb_body.append(in).append("p_width=\"");
		for (int i=0; i<n_points; i++) {
			sb_body.append(p_width[i]);
			if (n_points -1 != i) sb_body.append(",");
		}
		sb_body.append("\"\n");
		sb_body.append(indent).append("/>\n");
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_pipe";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_pipe EMPTY>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" d").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" p_width").append(TAG_ATTR2)
		;
	}

	/** Transform points falling within the given layer; translate by dx,dy and rotate by rot relative to origin xo,yo*/
	public void transformPoints(Layer layer, double dx, double dy, double rot, double xo, double yo) {
		if (-1 == n_points) setupForDisplay(); //reload
		for (int i=0; i<n_points; i++) {
			if (p_layer[i] == layer.getId()) {
				Displayable.transformPoint(p, i, dx, dy, rot, xo, yo);
				Displayable.transformPoint(p_l, i, dx, dy, rot, xo, yo);
				Displayable.transformPoint(p_r, i, dx, dy, rot, xo, yo);
			}
		}
		generateInterpolatedPoints(0.05);
		calculateBoundingBox(true);
		updateInDatabase("points");
	}

	public double[][][] generateMesh(double scale) {
		if (-1 == n_points) setupForDisplay(); //reload
		if (0 == n_points) return null;
		// at any given segment (bezier curve defined by 4 points):
		//   - resample to homogenize point distribution
		//   - if z doesn't change, use no intermediate sections in the tube
		//   - for each point:
		//       - add the section as 12 points, by rotating a perpendicular vector around the direction vector
		//       - if the point is the first one in the segment, use a direction vector averaged with the previous and the first in the segment (if it's not point 0, that is)
		
		// debug:
		return null;
	}

	/** Performs a deep copy of this object, without the links, unlocked and visible. */
	public Object clone() {
		final Pipe copy = new Pipe(project, project.getLoader().getNextId(), null != title ? title.toString() : null, width, height, alpha, true, new Color(color.getRed(), color.getGreen(), color.getBlue()), false, (AffineTransform)this.at.clone());
		// The data:
		if (-1 == n_points) setupForDisplay(); // load data
		copy.n_points = n_points;
		copy.p = new double[][]{(double[])this.p[0].clone(), (double[])this.p[1].clone()};
		copy.p_l = new double[][]{(double[])this.p_l[0].clone(), (double[])this.p_l[1].clone()};
		copy.p_r = new double[][]{(double[])this.p_r[0].clone(), (double[])this.p_r[1].clone()};
		copy.p_layer = (long[])this.p_layer.clone();
		copy.p_width = (double[])this.p_width.clone();
		copy.p_i = new double[][]{(double[])this.p_i[0].clone(), (double[])this.p_i[1].clone()};
		copy.p_width_i = (double[])this.p_width_i.clone();
		// add
		copy.layer = this.layer;
		copy.addToDatabase();
		// the snapshot has been already created in the Displayable constructor, but needs updating
		snapshot.remake();

		return copy;
	}

	public List generateTriangles(double scale, int parallels) {
		// check minimum requirements.
		if (parallels < 3) parallels = 3;
		//
		double[][][] all_points = generateJoints(parallels);
		int n = all_points.length;
		List list = new ArrayList();
		for (int i=0; i<n-1; i++) { //minus one since last is made with previous
			for (int j=0; j<parallels; j++) { //there are 12+12 triangles for each joint //it's upt to 12+1 because first point is repeated at the end
				// first triangle in the quad
				list.add(new Point3f((float)(all_points[i][j][0] * scale), (float)(all_points[i][j][1] * scale), (float)(all_points[i][j][2] * scale)));
				list.add(new Point3f((float)(all_points[i+1][j][0] * scale), (float)(all_points[i+1][j][1] * scale), (float)(all_points[i+1][j][2] * scale)));
				list.add(new Point3f((float)(all_points[i][j+1][0] * scale), (float)(all_points[i][j+1][1] * scale), (float)(all_points[i][j+1][2] * scale)));

				// second triangle in the quad

				list.add(new Point3f((float)(all_points[i+1][j][0] * scale), (float)(all_points[i+1][j][1] * scale), (float)(all_points[i+1][j][2] * scale)));
				list.add(new Point3f((float)(all_points[i+1][j+1][0] * scale), (float)(all_points[i+1][j+1][1] * scale), (float)(all_points[i+1][j+1][2] * scale)));
				list.add(new Point3f((float)(all_points[i][j+1][0] * scale), (float)(all_points[i][j+1][1] * scale), (float)(all_points[i][j+1][2] * scale)));
			}
		}
		return list;
	}

	/** From my former program, A_3D_Editing.java and Pipe.java  */
	private double[][][] generateJoints(final int parallels) {
		if (-1 == n_points) setupForDisplay();
		
		// local pointers, since they may be transformed
		double[][] p = this.p;
		double[][] p_r = this.p_r;
		double[][] p_l = this.p_l;
		double[][] p_i = this.p_i;
		double[] p_width = this.p_width;
		double[] p_width_i = this.p_width_i;
		if (!this.at.isIdentity()) {
			final Object[] ob = getTransformedData();
			p = (double[][])ob[0];
			p_l = (double[][])ob[1];
			p_r = (double[][])ob[2];
			p_i = (double[][])ob[3];
			p_width = (double[])ob[4];
			p_width_i = (double[])ob[5];
		}

		final int n = p_i[0].length;
		final int mm = n_points;
		final double[] z_values = new double[n];
		final int interval_points = n / (mm-1);
		double z_val = 0;
		double z_val_next = 0;
		double z_diff = 0;
		int c = 0;
		double delta = 0;

		for (int j=0; j<mm-1; j++) {
			z_val = layer_set.getLayer(p_layer[j]).getZ();
			z_val_next = layer_set.getLayer(p_layer[j+1]).getZ();
			z_diff = z_val_next - z_val;
			delta = z_diff/interval_points;
			z_values[c] = (0 == j ? z_val : z_values[c-1]) + delta;
			for (int k=1; k<interval_points; k++) {
				c++;
				z_values[c] = z_values[c-1] + delta;
			}
			c++;
		}
		/*
		for (int j=0; j<mm-1; j++) {
			z_val = layer_set.getLayer(p_layer[j]).getZ();
			z_val_next = layer_set.getLayer(p_layer[j+1]).getZ();
			z_diff = z_val_next - z_val;
			delta = z_diff/interval_points;
			z_values[c] = z_val + delta;
			for (int k=1; k<interval_points; k++) {
				c++;
				z_values[c] = z_values[c-1] + delta;
			}
			c++;
		}
		*/
		//setting last point
		z_values[n-1] = layer_set.getLayer(p_layer[mm-1]).getZ();

		double[] px = p_i[0];
		double[] py = p_i[1];
		double[] pz = z_values;

		/** Testing 3D resample*/
		/* // doesn't work, indexoutofbounds exception
		if (true) {
			SV sv = null;
			try {
				sv = new SV(p_i[0], p_i[1], z_values, 0);
			} catch (Exception e) {
				new IJError(e);
			}
			if (null != sv) {
				sv.resample(sv.getAverageDelta());
				px = sv.getX();
				py = sv.getY();
				pz = sv.getZ();
				n = sv.getLength();
			}
		}
		*/


		double[][][] all_points = new double[n+2][parallels+1][3];
		int extra = 1; // this was zero when not doing capping
		for (int cap=0; cap<parallels+1; cap++) {
			all_points[0][cap][0] = px[0];//p_i[0][0]; //x
			all_points[0][cap][1] = py[0]; //p_i[1][0]; //y
			all_points[0][cap][2] = pz[0]; //z_values[0];
			all_points[all_points.length-1][cap][0] = px[n-1]; //p_i[0][p_i[0].length-1];
			all_points[all_points.length-1][cap][1] = py[n-1]; //p_i[1][p_i[0].length-1];
			all_points[all_points.length-1][cap][2] = pz[n-1]; //z_values[z_values.length-1];
		}
		double angle = 2*Math.PI/parallels; //Math.toRadians(30);

		Vector3 v3_P12;
		Vector3 v3_PR;
		Vector3[] circle = new Vector3[parallels+1];
		double sinn, coss;
		int half_parallels = parallels/2;
		for (int i=0; i<n-1; i++) {
			//First vector: from one realpoint to the next
			//v3_P12 = new Vector3(p_i[0][i+1] - p_i[0][i], p_i[1][i+1] - p_i[1][i], z_values[i+1] - z_values[i]);
			v3_P12 = new Vector3(px[i+1] - px[i], py[i+1] - py[i], pz[i+1] - pz[i]);

			//Second vector: ortogonal to v3_P12, made by cross product between v3_P12 and a modifies v3_P12 (either y or z set to 0)

			//checking that v3_P12 has not z set to 0, in which case it woundn´t be different and then the cross product not give an ortogonal vector as output

			//chosen random vector: the same vector, but with x = 0;
			/* matrix:
				1 1 1		1 1 1 				1 1 1				1 1 1
				v1 v2 v3	P12[0] P12[1] P12[2]		P12[0] P12[1] P12[2]		P12[0] P12[1] P12[2]
				w1 w2 w3	P12[0]+1 P12[1] P12[2]		P12[0]+1 P12[1]+1 P12[2]+1	P12[0] P12[1] P12[2]+1

			   cross product: v ^ w = (v2*w3 - w2*v3, v3*w1 - v1*w3, v1*w2 - w1*v2);
			   
			   cross product of second: v ^ w = (b*(c+1) - c*(b+1), c*(a+1) - a*(c+1) , a*(b+1) - b*(a+1))
			   				  = ( b - c           , c - a             , a - b            )
							  
			   cross product of third: v ^ w = (b*(c+1) - b*c, c*a - a*(c+1), a*b - b*a)
			   				   (b		 ,-a            , 0);
							   (v3_P12.y	 ,-v3_P12.x     , 0);
							   
							   
			Reasons why I use the third:
				-Using the first one modifies the x coord, so it generates a plane the ortogonal of which will be a few degrees different when z != 0 and when z =0,
				 thus responsible for soft shiftings at joints where z values change
				-Adding 1 to the z value will produce the same plane whatever the z value, thus avoiding soft shiftings at joints where z values are different
				-Then, the third allows for very fine control of the direction that the ortogonal vector takes: simply manipulating the x coord of v3_PR, voilà.
							   
			*/

			// BELOW if-else statements needed to correct the orientation of vectors, so there's no discontinuity
			if (v3_P12.y < 0) {
				v3_PR = new Vector3(v3_P12.y, -v3_P12.x, 0);
				v3_PR = v3_PR.normalize(v3_PR);
				v3_PR = v3_PR.scale(p_width_i[i], v3_PR);

				//vectors are perfectly normalized and scaled
				//The problem then must be that they are not properly ortogonal and so appear to have a smaller width.
				//   -not only not ortogonal but actually messed up in some way, i.e. bad coords.

				circle[half_parallels] = v3_PR;
				for (int q=half_parallels+1; q<parallels+1; q++) {
					sinn = Math.sin(angle*(q-half_parallels));
					coss = Math.cos(angle*(q-half_parallels));
					circle[q] = rotate_v_around_axis(v3_PR, v3_P12, sinn, coss);
				}
				circle[0] = circle[parallels];
				for (int qq=1; qq<half_parallels; qq++) {
					sinn = Math.sin(angle*(qq+half_parallels));
					coss = Math.cos(angle*(qq+half_parallels));
					circle[qq] = rotate_v_around_axis(v3_PR, v3_P12, sinn, coss);
				}
			} else {
				v3_PR = new Vector3(-v3_P12.y, v3_P12.x, 0);           //thining problems disappear when both types of y coord are equal, but then shifting appears
				/*
				Observations:
					-if y coord shifted, then no thinnings but yes shiftings
					-if x coord shifted, THEN PERFECT
					-if both shifted, then both thinnings and shiftings
					-if none shifted, then no shiftings but yes thinnings
				*/

				v3_PR = v3_PR.normalize(v3_PR);
				v3_PR = v3_PR.scale(p_width_i[i], v3_PR);

				circle[0] = v3_PR;
				for (int q=1; q<parallels; q++) {
					sinn = Math.sin(angle*q);
					coss = Math.cos(angle*q);
					circle[q] = rotate_v_around_axis(v3_PR, v3_P12, sinn, coss);
				}
				circle[parallels] = v3_PR;
			}
			// Adding points to main array
			for (int j=0; j<parallels+1; j++) {
				all_points[i+extra][j][0] = /*p_i[0][i]*/ px[i] + circle[j].x;
				all_points[i+extra][j][1] = /*p_i[1][i]*/ py[i] + circle[j].y;
				all_points[i+extra][j][2] = /*z_values[i]*/ pz[i] + circle[j].z;
			}
		}
		for (int k=0; k<parallels+1; k++) {
			all_points[n-1+extra][k][0] = /*p_i[0][n-1]*/ px[n-1] + circle[k].x;
			all_points[n-1+extra][k][1] = /*p_i[1][n-1]*/ py[n-1] + circle[k].y;
			all_points[n-1+extra][k][2] = /*z_values[n-1]*/ pz[n-1] + circle[k].z;
		}
		return all_points;
	}

	/** From my former program, A_3D_Editing.java and Pipe.java */
	private Vector3 rotate_v_around_axis(Vector3 v, Vector3 axis, double sin, double cos) {

		Vector3 result = new Vector3();
		Vector3 r = axis.normalize(axis);

		result.set((cos + (1-cos) * r.x * r.x) * v.x + ((1-cos) * r.x * r.y - r.z * sin) * v.y + ((1-cos) * r.x * r.z + r.y * sin) * v.z,
		           ((1-cos) * r.x * r.y + r.z * sin) * v.x + (cos + (1-cos) * r.y * r.y) * v.y + ((1-cos) * r.y * r.z - r.x * sin) * v.z,
		           ((1-cos) * r.y * r.z - r.y * sin) * v.x + ((1-cos) * r.y * r.z + r.x * sin) * v.y + (cos + (1-cos) * r.z * r.z) * v.z);

		/*
		result.x += (cos + (1-cos) * r.x * r.x) * v.x;
		result.x += ((1-cos) * r.x * r.y - r.z * sin) * v.y;
		result.x += ((1-cos) * r.x * r.z + r.y * sin) * v.z;

		result.y += ((1-cos) * r.x * r.y + r.z * sin) * v.x;
		result.y += (cos + (1-cos) * r.y * r.y) * v.y;
		result.y += ((1-cos) * r.y * r.z - r.x * sin) * v.z;

		result.z += ((1-cos) * r.y * r.z - r.y * sin) * v.x;
		result.z += ((1-cos) * r.y * r.z + r.x * sin) * v.y;
		result.z += (cos + (1-cos) * r.z * r.z) * v.z;
		*/
		return result;
	}

	private Object[] getTransformedData() {
		final double[][] p = transformPoints(this.p);
		final double[][] p_l = transformPoints(this.p_l);
		final double[][] p_r = transformPoints(this.p_r);
		final double[][] p_i = transformPoints(this.p_i);
		// p_width: same rule as for Ball: average of x and y
		double[][] pw = new double[2][n_points];
		for (int i=0; i<n_points; i++) {
			pw[0][i] = this.p[0][i] + p_width[i]; //built relative to the untransformed points!
			pw[1][i] = this.p[1][i] + p_width[i];
		}
		pw = transformPoints(pw);
		final double[] p_width = new double[n_points];
		for (int i=0; i<n_points; i++) {
			// plain average of differences in X and Y axis, relative to the transformed points.
			p_width[i] = (Math.abs(pw[0][i] - p[0][i]) + Math.abs(pw[1][i] - p[1][i])) / 2;
		}
		// same with p_width_i
		double[][] pwi = new double[2][p_i[0].length];
		for (int i=0; i<p_i[0].length; i++) {
			pwi[0][i] = this.p_i[0][i] + p_width_i[i]; //built relative to the untransformed points!
			pwi[1][i] = this.p_i[1][i] + p_width_i[i];
		}
		pwi = transformPoints(pwi);
		final double[] p_width_i = new double[p_i[0].length];
		for (int i=0; i<p_i[0].length; i++) {
			// plain average of differences in X and Y axis, relative to the transformed points.
			p_width_i[i] = (Math.abs(pwi[0][i] - p_i[0][i]) + Math.abs(pwi[1][i] - p_i[1][i])) / 2;
		}

		return new Object[]{p, p_l, p_r, p_i, p_width, p_width_i};
	}
}
