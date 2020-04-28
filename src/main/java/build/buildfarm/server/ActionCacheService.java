// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.server;

import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.UpdateActionResultRequest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.grpc.TracingMetadataUtils;
import build.buildfarm.instance.Instance;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.logging.Level;
import javax.annotation.Nullable;
import java.util.logging.Logger;

public class ActionCacheService extends ActionCacheGrpc.ActionCacheImplBase {
  public static final Logger logger = Logger.getLogger(ActionCacheService.class.getName());

  private final Instances instances;
  private final Runnable onRequest;

  public ActionCacheService(Instances instances, Runnable onRequest) {
    this.instances = instances;
    this.onRequest = onRequest;
  }

  @Override
  public void getActionResult(
      GetActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
    Instance instance;
    try {
      instance = instances.get(request.getInstanceName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    ListenableFuture<ActionResult> resultFuture = instance.getActionResult(
        DigestUtil.asActionKey(request.getActionDigest()),
        TracingMetadataUtils.fromCurrentContext());

    addCallback(
        resultFuture,
        new FutureCallback<ActionResult>() {
          @Override
          public void onSuccess(@Nullable ActionResult actionResult) {
            try {
              if (actionResult == null) {
                responseObserver.onError(Status.NOT_FOUND.asException());
              } else {
                responseObserver.onNext(actionResult);
                responseObserver.onCompleted();
              }
            } catch (StatusRuntimeException e) {
              onFailure(e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            logger.log(Level.WARNING, String.format("getActionResult(%s): %s", request.getInstanceName(), DigestUtil.toString(request.getActionDigest())), t);
            Status status = Status.fromThrowable(t);
            if (status.getCode() != Code.CANCELLED) {
              try {
                responseObserver.onError(status.asException());
              } catch (StatusRuntimeException e) {
                // ignore
              }
            }
          }
        },
        directExecutor());
    onRequest.run();
  }

  @Override
  public void updateActionResult(
      UpdateActionResultRequest request, StreamObserver<ActionResult> responseObserver) {
    Instance instance;
    try {
      instance = instances.get(request.getInstanceName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    ActionResult actionResult = request.getActionResult();
    try {
      instance.putActionResult(DigestUtil.asActionKey(request.getActionDigest()), actionResult);

      responseObserver.onNext(actionResult);
      responseObserver.onCompleted();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
