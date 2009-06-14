package freenet.client.connection;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import freenet.client.FreenetJs;
import freenet.client.UpdaterConstants;

public class KeepaliveManager implements IConnectionManager {

	KeepaliveTimer	timer	= new KeepaliveTimer();

	@Override
	public void closeConnection() {
		timer.cancel();
	}

	@Override
	public void openConnection() {
		timer.scheduleRepeating(UpdaterConstants.KEEPALIVE_INTERVAL_SECONDS * 1000);
	}

	private class KeepaliveTimer extends Timer {
		@Override
		public void run() {
			try {
				new RequestBuilder(RequestBuilder.GET, IConnectionManager.keepalivePath + "?requestId=" + FreenetJs.requestId).sendRequest(null, new RequestCallback(){
				
					@Override
					public void onResponseReceived(Request request, Response response) {
						if(response.getText().compareTo(UpdaterConstants.SUCCESS)!=0){
							closeConnection();
						}
					}
				
					@Override
					public void onError(Request request, Throwable exception) {
						closeConnection();
					}
				});
			} catch (RequestException e) {
				FreenetJs.log("Error at KeepaliveTimer!");
			}
		}
	}
}
