package nonstar.controller;

import nonstar.basic.Flow;
import nonstar.basic.Switch;

public class Nonstar extends NonstarBase {

	boolean flag = true;
	public void on_start()  {
	
	}
	public Flow on_req(Switch src, Switch dst)  {
			return null;
	}
	
	
	@Override
	public void onstart() {
		on_start();
	}

	@Override
	public Flow onreq(Switch src, Switch dst) {
		return on_req(src, dst);
	}
	
	public Nonstar(NetworkTopo env) {
		super(env);	
	}

}