/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.metrics.impl;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Queue;
import hudson.model.Run;
import java.util.Optional;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;

/**
 * Resolves a {@link Run} from a {@link Queue.Executable}
 */
public abstract class RunResolver implements ExtensionPoint {
    /**
     * Attempts to resolve the run from the executable.
     *
     * @param executable the executable.
     * @return the run or {@code null}
     */
    @CheckForNull
    protected abstract Run<?, ?> runOf(@NonNull Queue.Executable executable);

    /**
     * Resolves a {@link Queue.Executable} into the {@link Run} that it belongs to.
     *
     * @param executable the executable.
     * @return the run (may be {@link Optional#empty()}.
     */
    @NonNull
    public static Optional<Run<?, ?>> resolve(@NonNull Queue.Executable executable) {
        for (RunResolver r : ExtensionList.lookup(RunResolver.class)) {
            Run<?, ?> run = r.runOf(executable);
            if (run != null) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    /**
     * Standard implementation of {@link RunResolver} that resolves the case where the {@link Queue.Executable}
     * implements {@link Run}
     */
    @Extension
    public static class ImplementsRun extends RunResolver {

        /**
         * {@inheritDoc}
         */
        @Override
        protected Run<?, ?> runOf(@NonNull Queue.Executable executable) {
            if (executable instanceof Run) {
                return (Run<?, ?>) executable;
            }
            return null;
        }
    }

    /**
     * Pipeline specific implementation of {@link RunResolver}
     */
    @OptionalExtension(requireClasses = ExecutorStepExecution.PlaceholderTask.class)
    public static class WorkflowPlaceholderTask extends RunResolver {

        /**
         * {@inheritDoc}
         */
        @Override
        protected Run<?, ?> runOf(@NonNull Queue.Executable executable) {
            if (executable.getParent() instanceof ExecutorStepExecution.PlaceholderTask) {
                return ((ExecutorStepExecution.PlaceholderTask) executable.getParent()).run();
            }
            return null;
        }
    }
}
