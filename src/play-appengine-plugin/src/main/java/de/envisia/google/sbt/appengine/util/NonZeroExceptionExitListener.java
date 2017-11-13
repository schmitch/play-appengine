package de.envisia.google.sbt.appengine.util;

import com.google.cloud.tools.appengine.api.AppEngineException;
import com.google.cloud.tools.appengine.cloudsdk.process.ProcessExitListener;

/**
 * Exit listener that throws a {@link AppEngineException} on a non-zero exit value.
 */
// Is this being used by the clients?  I'm not a big fan of unused code and it has no tests.
public class NonZeroExceptionExitListener implements ProcessExitListener {

    @Override
    public void onExit(int exitCode) {
        if (exitCode != 0) {
            throw new AppEngineException("Non zero exit: " + exitCode);
        }
    }

}
