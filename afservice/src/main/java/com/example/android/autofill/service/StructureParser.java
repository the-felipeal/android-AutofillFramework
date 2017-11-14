/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.example.android.autofill.service;

import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillValue;

import com.example.android.autofill.service.datasource.SharedPrefsDigitalAssetLinksRepository;
import com.example.android.autofill.service.model.FilledAutofillField;
import com.example.android.autofill.service.model.FilledAutofillFieldCollection;

import static com.example.android.autofill.service.Util.logd;
import static com.example.android.autofill.service.Util.logw;

/**
 * Parser for an AssistStructure object. This is invoked when the Autofill Service receives an
 * AssistStructure from the client Activity, representing its View hierarchy. In this sample, it
 * parses the hierarchy and collects autofill metadata from {@link ViewNode}s along the way.
 */
final class StructureParser {
    private final AutofillFieldMetadataCollection mAutofillFields =
            new AutofillFieldMetadataCollection();
    private final Context mContext;
    private final AssistStructure mStructure;
    private FilledAutofillFieldCollection mFilledAutofillFieldCollection;

    StructureParser(Context context, AssistStructure structure) {
        mContext = context;
        mStructure = structure;
    }

    public void parseForFill() {
        parse(true);
    }

    public void parseForSave() {
        parse(false);
    }

    /**
     * Traverse AssistStructure and add ViewNode metadata to a flat list.
     */
    private void parse(boolean forFill) {
        logd("Parsing structure for %s", mStructure.getActivityComponent());
        int nodes = mStructure.getWindowNodeCount();
        mFilledAutofillFieldCollection = new FilledAutofillFieldCollection();
        StringBuilder webDomain = new StringBuilder();
        for (int i = 0; i < nodes; i++) {
            WindowNode node = mStructure.getWindowNodeAt(i);
            ViewNode view = node.getRootViewNode();
            parseLocked(forFill, view, webDomain);
        }
        if (webDomain.length() > 0) {
            String packageName = mStructure.getActivityComponent().getPackageName();
            boolean valid = SharedPrefsDigitalAssetLinksRepository.getInstance().isValid(mContext,
                    webDomain.toString(), packageName);
            if (!valid) {
                logw("Domain %s is NOT valid for %s, but ignoring it", webDomain, packageName);
//                throw new SecurityException(mContext.getString(
//                        R.string.invalid_link_association, webDomain, packageName));
            } else {
                logd("Domain %s is valid for %s", webDomain, packageName);
            }
        } else {
            logd("no web domain");
        }
    }

    private void parseLocked(boolean forFill, ViewNode viewNode, StringBuilder validWebDomain) {
        String webDomain = viewNode.getWebDomain();
        if (webDomain != null) {
            logd("child web domain: %s", webDomain);
            if (validWebDomain.length() > 0) {
                if (!webDomain.equals(validWebDomain.toString())) {
                    throw new SecurityException("Found multiple web domains: valid= "
                            + validWebDomain + ", child=" + webDomain);
                }
            } else {
                validWebDomain.append(webDomain);
            }
        }

        boolean hasAutofillHints = viewNode.getAutofillHints() != null;
        ViewStructure.HtmlInfo htmlInfo = viewNode.getHtmlInfo();
        boolean hasHtmlInfo = htmlInfo != null;


        if (hasAutofillHints || hasHtmlInfo) {
            String[] filteredHints = null;
            if (hasAutofillHints) {
                filteredHints = AutofillHints.filterForSupportedHints(
                        viewNode.getAutofillHints());
            }
            if (hasHtmlInfo && filteredHints == null) {
                logd("Scanning HtmlInfo: %s", htmlInfo.getAttributes());
                for (Pair<String, String> attr : htmlInfo.getAttributes()) {
                    logd("Scanning attribute %s=%s", attr.first, attr.second);
                    if ("type".equals(attr.first)) {
                        switch (attr.second) {
                            case "email":
                                logd("Found email");
                                filteredHints = new String[] {View.AUTOFILL_HINT_EMAIL_ADDRESS};
                                break;
                            case "password":
                                logd("Found password");
                                filteredHints = new String[] {View.AUTOFILL_HINT_PASSWORD};
                                break;
                            default:
                                logd("Ignoring type '%s'", attr.second);
                        }
                    } else {
                        logd("Ignoring attr " + attr.second);
                    }
                }
            }
            if (filteredHints != null && filteredHints.length > 0) {
                if (forFill) {
                    mAutofillFields.add(new AutofillFieldMetadata(viewNode, filteredHints));
                } else {
                    FilledAutofillField filledAutofillField =
                            new FilledAutofillField(viewNode.getAutofillHints());
                    AutofillValue autofillValue = viewNode.getAutofillValue();
                    if (autofillValue.isText()) {
                        // Using toString of AutofillValue.getTextValue in order to save it to
                        // SharedPreferences.
                        filledAutofillField.setTextValue(autofillValue.getTextValue().toString());
                    } else if (autofillValue.isDate()) {
                        filledAutofillField.setDateValue(autofillValue.getDateValue());
                    } else if (autofillValue.isList()) {
                        filledAutofillField.setListValue(viewNode.getAutofillOptions(),
                                autofillValue.getListValue());
                    }
                    mFilledAutofillFieldCollection.add(filledAutofillField);
                }
            }
        }

        int childrenSize = viewNode.getChildCount();
        if (childrenSize > 0) {
            for (int i = 0; i < childrenSize; i++) {
                parseLocked(forFill, viewNode.getChildAt(i), validWebDomain);
            }
        }
    }

    public AutofillFieldMetadataCollection getAutofillFields() {
        return mAutofillFields;
    }

    public FilledAutofillFieldCollection getClientFormData() {
        return mFilledAutofillFieldCollection;
    }
}
