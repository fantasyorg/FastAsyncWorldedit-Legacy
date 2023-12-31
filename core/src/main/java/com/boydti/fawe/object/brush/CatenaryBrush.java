package com.boydti.fawe.object.brush;

import com.boydti.fawe.config.BBC;
import com.boydti.fawe.object.brush.visualization.VisualExtent;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.util.Location;

import java.util.Arrays;
import java.util.List;

public class CatenaryBrush implements Brush, ResettableTool {

    private final boolean shell, select, direction;
    private final double slack;

    private Vector pos1;
    private Vector pos2;
    private Vector vertex;

    public CatenaryBrush(boolean shell, boolean select, boolean direction, double lengthFactor) {
        this.shell = shell;
        this.select = select;
        this.direction = direction;
        this.slack = lengthFactor;
    }

    @Override
    public void build(EditSession editSession, Vector pos2, final Pattern pattern, double size) throws MaxChangedBlocksException {
        boolean visual = (editSession.getExtent() instanceof VisualExtent);
        if (pos1 == null || pos2.equals(pos1)) {
            if (!visual) {
                pos1 = pos2;
                BBC.BRUSH_LINE_PRIMARY.send(editSession.getPlayer(), pos2);
            }
            return;
        }
        if (this.vertex == null) {
            vertex = getVertex(pos1, pos2, slack);
            if (this.direction) {
                BBC.BRUSH_CATENARY_DIRECTION.send(editSession.getPlayer(), 2);
                return;
            }
        } else if (this.direction) {
            Location loc = editSession.getPlayer().getPlayer().getLocation();
            Vector facing = loc.getDirection().normalize();
            Vector midpoint = pos1.add(pos2).divide(2);
            Vector offset = midpoint.subtract(vertex);
            vertex = midpoint.add(facing.multiply(offset.length()));
        }
        List<Vector> nodes = Arrays.asList(pos1, vertex, pos2);
        vertex = null;
        editSession.drawSpline(pattern, nodes, 0, 0, 0, 10, size, !shell);
        if (!visual) {
            BBC.BRUSH_LINE_SECONDARY.send(editSession.getPlayer());
            if (!select) {
                pos1 = null;
                return;
            } else {
                pos1 = pos2;
            }
        }
    }

    @Override
    public boolean reset() {
        pos1 = null;
        return true;
    }

    public static Vector getVertex(Vector pos1, Vector pos2, double lenPercent) {
        if (lenPercent <= 1) return Vector.getMidpoint(pos1, pos2);
        double curveLen = pos1.distance(pos2) * lenPercent;
        double dy = pos2.getY() - pos1.getY();
        double dx = pos2.getX() - pos1.getX();
        double dz = pos2.getZ() - pos1.getZ();
        double dh = Math.sqrt(dx * dx + dz * dz);
        double g = Math.sqrt(curveLen * curveLen - dy * dy) / 2;
        double a = 0.00001;
        for (; g < a * Math.sinh(dh / (2 * a)); a *= 1.00001) ;
        double vertX = (dh - a * Math.log((curveLen + dy) / (curveLen - dy))) / 2.0;
        double z = (dh / 2) / a;
        double oY = (dy - curveLen * (Math.cosh(z) / Math.sinh(z))) / 2;
        double vertY = a * 1 + oY;
        return pos1.add(pos2.subtract(pos1).multiply(vertX / dh).add(0, vertY, 0)).round();
    }
}
