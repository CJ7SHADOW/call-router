package com.kg4bhr.callrouter;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap to turn call routing on or off. */
public class RoutingTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Prefs p = new Prefs(this);
        p.setEnabled(!p.enabled());
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        Prefs p = new Prefs(this);
        boolean on = p.enabled();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Call routing");
        t.setSubtitle(!on ? "Off · all calls on cell" : p.dryRun() ? "Dry run" : "On");
        t.updateTile();
    }
}
