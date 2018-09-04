/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.autofill.service.simple;

import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.Dataset.Builder;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.example.android.autofill.service.MyAutofillService;
import com.example.android.autofill.service.R;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A very basic {@link AutofillService} implementation that only shows dynamic-generated datasets
 * and don't persist the saved data.
 *
 * <p>The goal of this class is to provide a simple autofill service implementation that is easy
 * to understand and extend, but it should <strong>not</strong> be used as-is on real apps because
 * it lacks fundamental security requirements such as data partitioning and package verification
 * &mdashthese requirements are fullfilled by {@link MyAutofillService}.
 */
public class BasicService extends AutofillService {

    private static final String TAG = "BasicService";

    /**
     * Number of datasets sent on each request - we're simple, that value is hardcoded in our DNA!
     */
    static final int NUMBER_DATASETS = 4;

    private final boolean mAuthenticateDatasets;
    private final boolean mAuthenticateResponses;

    public BasicService() {
        this(false, false);
    }

    protected BasicService(boolean authenticateDatasets, boolean authenticateResponses) {
        mAuthenticateDatasets = authenticateDatasets;
        mAuthenticateResponses = authenticateResponses;
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
            FillCallback callback) {
        Log.d(TAG, "onFillRequest()");

        // Find autofillable fields
        AssistStructure structure = getLatestAssistStructure(request);

        ArrayMap<String, AutofillId> fields = getAutofillableFields(structure, request.getFlags());
        Log.d(TAG, "autofillable fields:" + fields);

        if (fields.isEmpty()) {
            showMessage("Service could not figure out how to autofill this screen");
            callback.onSuccess(null);
            return;
        }

        if (mAuthenticateResponses) {
            int size = fields.size();
            String[] hints = new String[size];
            AutofillId[] ids = new AutofillId[size];
            for (int i = 0; i < size; i++) {
                hints[i] = fields.keyAt(i);
                ids[i] = fields.valueAt(i);
            }

            IntentSender authentication =
                    SimpleAuthActivity.newIntentSenderForResponse(this, hints, ids);
            RemoteViews presentation = newDatasetPresentation(getPackageName(),
                        "Tap to auth response");

            FillResponse response = new FillResponse.Builder()
                    .setAuthentication(ids, authentication, presentation).build();
            callback.onSuccess(response);
            return;
        }

        // Create the base response
        FillResponse response = createResponse(this, fields, NUMBER_DATASETS,
                mAuthenticateDatasets);
        callback.onSuccess(response);
    }

    static FillResponse createResponse(@NonNull Context context,
            @NonNull ArrayMap<String, AutofillId> fields, int numDatasets,
            boolean authenticateDatasets) {
        String packageName = context.getPackageName();
        FillResponse.Builder response = new FillResponse.Builder();
        // 1.Add the dynamic datasets
        for (int i = 1; i <= numDatasets; i++) {
            Dataset unlockedDataset = newUnlockedDataset(fields, packageName, i);
            if (authenticateDatasets) {
                Dataset.Builder lockedDataset = new Dataset.Builder();
                for (Entry<String, AutofillId> field : fields.entrySet()) {
                    String hint = field.getKey();
                    AutofillId id = field.getValue();
                    String value = hint + i;
                    IntentSender authentication =
                            SimpleAuthActivity.newIntentSenderForDataset(context, unlockedDataset);
                    RemoteViews presentation = newDatasetPresentation(packageName,
                            "Tap to auth " + value);
                    lockedDataset.setValue(id, null, presentation)
                            .setAuthentication(authentication);
                }
                response.addDataset(lockedDataset.build());
            } else {
                response.addDataset(unlockedDataset);
            }
        }

        // 2.Add save info
        Collection<AutofillId> ids = fields.values();
        AutofillId[] requiredIds = new AutofillId[ids.size()];
        ids.toArray(requiredIds);
        response.setSaveInfo(
                // We're simple, so we're generic
                new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_GENERIC, requiredIds).build());

        // 3.Profit!
        return response.build();
    }

    static Dataset newUnlockedDataset(@NonNull Map<String, AutofillId> fields,
            @NonNull String packageName, int i) {
        Dataset.Builder dataset = new Dataset.Builder();
        for (Entry<String, AutofillId> field : fields.entrySet()) {
            String hint = field.getKey();
            AutofillId id = field.getValue();
            String value = hint + i;

            // We're simple - our dataset values are hardcoded as "hintN" (for example,
            // "username1", "username2") and they're displayed as such, except if they're a
            // password
            String displayValue = hint.contains("password") ? "password for #" + i : value;
            RemoteViews presentation = newDatasetPresentation(packageName, displayValue);
            dataset.setValue(id, AutofillValue.forText(value), presentation);
        }

        return dataset.build();
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        Log.d(TAG, "onSaveRequest()");
        showMessage("Save not supported");
        callback.onSuccess();
    }

    /**
     * Parses the {@link AssistStructure} representing the activity being autofilled, and returns a
     * map of autofillable fields (represented by their autofill ids) mapped by the hint associate
     * with them.
     *
     * <p>An autofillable field is a {@link ViewNode} whose {@link #getHint(ViewNode)} metho
     */
    @NonNull
    private ArrayMap<String, AutofillId> getAutofillableFields(@NonNull AssistStructure structure,
            int flags) {
        ArrayMap<String, AutofillId> fields = new ArrayMap<>();
        int nodes = structure.getWindowNodeCount();
        for (int i = 0; i < nodes; i++) {
            ViewNode node = structure.getWindowNodeAt(i).getRootViewNode();
            addAutofillableFields(fields, node, flags);
        }
        return fields;
    }

    /**
     * Adds any autofillable view from the {@link ViewNode} and its descendants to the map.
     */
    private void addAutofillableFields(@NonNull Map<String, AutofillId> fields,
            @NonNull ViewNode node, int flags) {
        int type = node.getAutofillType();
        String hint = getHint(node, flags);
        if (hint != null) {
            AutofillId id = node.getAutofillId();
            if (!fields.containsKey(hint)) {
                Log.v(TAG, "Setting hint " + hint + " on " + id);
                fields.put(hint, id);
            } else {
                Log.v(TAG, "Ignoring hint " + hint + " on " + id
                        + " because it was already set");
            }
        }
        int childrenSize = node.getChildCount();
        for (int i = 0; i < childrenSize; i++) {
            addAutofillableFields(fields, node.getChildAt(i), flags);
        }
    }

    /**
     * Gets the autofill hint associated with the given node.
     *
     * <p>By default it just return the first entry on the node's
     * {@link ViewNode#getAutofillHints() autofillHints} (when available), but subclasses could
     * extend it to use heuristics when the app developer didn't explicitly provide these hints.
     *
     */
    @Nullable
    protected String getHint(@NonNull ViewNode node, int flags) {
        String[] hints = node.getAutofillHints();
        if (hints == null) return null;

        // We're simple, we only care about the first hint
        String hint = hints[0].toLowerCase();
        return hint;
    }

    /**
     * Helper method to get the {@link AssistStructure} associated with the latest request
     * in an autofill context.
     */
    @NonNull
    private static AssistStructure getLatestAssistStructure(@NonNull FillRequest request) {
        List<FillContext> fillContexts = request.getFillContexts();
        return fillContexts.get(fillContexts.size() - 1).getStructure();
    }

    /**
     * Helper method to create a dataset presentation with the given text.
     */
    @NonNull
    private static RemoteViews newDatasetPresentation(@NonNull String packageName,
            @NonNull CharSequence text) {
        RemoteViews presentation =
                new RemoteViews(packageName, R.layout.multidataset_service_list_item);
        presentation.setTextViewText(R.id.text, text);
        presentation.setImageViewResource(R.id.icon, R.mipmap.ic_launcher);
        return presentation;
    }

    /**
     * Displays a toast with the given message.
     */
    private void showMessage(@NonNull CharSequence message) {
        Log.i(TAG, message.toString());
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
