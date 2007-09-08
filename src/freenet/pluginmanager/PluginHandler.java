package freenet.pluginmanager;

import freenet.support.Logger;
import freenet.support.OOMHandler;

/**
 * Methods to handle a specific plugin (= set it up and start it)
 * 
 * @author cyberdo
 */
public class PluginHandler {

	/**
	 * Will get all needed info from the plugin, put it into the Wrapper. Then
	 * the Pluginstarter will be greated, and the plugin fedto it, starting the
	 * plugin.
	 * 
	 * the pluginInfoWrapper will then be returned
	 * 
	 * @param plug
	 */
	public static PluginInfoWrapper startPlugin(PluginManager pm, String filename, FredPlugin plug, PluginRespirator pr) {
		final PluginInfoWrapper pi = new PluginInfoWrapper(plug, filename);
		final PluginStarter ps = new PluginStarter(pr, pi);
		if(!pi.isThreadlessPlugin()) // No point otherwise
			pi.setThread(ps);
		
		ps.setPlugin(pm, plug);
		// Run after startup
		// FIXME this is horrible, wastes a thread, need to make PluginStarter a Runnable 
		// not a Thread, and then deal with the consequences of that (removePlugin(Thread)) ...
		pm.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				if (!pi.isThreadlessPlugin())
					ps.start();
				else
					ps.run();
			}
		}, 0);
		return pi;
	}
	
	private static class PluginStarter extends Thread {
		private Object plugin = null;
		private PluginRespirator pr;
		private PluginManager pm = null;
		final PluginInfoWrapper pi;
		
		public PluginStarter(PluginRespirator pr, PluginInfoWrapper pi) {
			this.pr = pr;
			this.pi = pi;
			setDaemon(true);
		}
		
		public void setPlugin(PluginManager pm, Object plugin) {
			this.plugin = plugin;
			this.pm = pm;
		}
		
		public void run() {
			if (plugin instanceof FredPlugin) {
				try {
					((FredPlugin)plugin).runPlugin(pr);
				} catch (OutOfMemoryError e) {
					OOMHandler.handleOOM(e);
				} catch (Throwable t) {
					Logger.normal(this, "Caught Throwable while running plugin: "+t, t);
					System.err.println("Caught Throwable while running plugin: "+t);
					t.printStackTrace();
				}
				if(!(plugin instanceof FredPluginThreadless)) {
					pi.unregister(pm); // If not already unregistered
					pm.removePlugin(pi);
				}
			} else {
				// If not FredPlugin, then the whole thing is aborted,
				// and then this method will return, killing the thread
				return;
			}
		}
		
	}
}
