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

package android.view.textclassifier;

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.LocaleList;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Testing {@link TextClassifierTest} APIs on local and system textclassifier.
 * <p>
 * Tests are skipped if such a textclassifier does not exist.
 */
@SmallTest
@RunWith(Parameterized.class)
public class TextClassifierTest {
    private static final String LOCAL = "local";
    private static final String SYSTEM = "system";

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<Object> textClassifierTypes() {
        return Arrays.asList(LOCAL, SYSTEM);
    }

    @Parameterized.Parameter
    public String mTextClassifierType;

    private static final LocaleList LOCALES = LocaleList.forLanguageTags("en-US");
    private static final String NO_TYPE = null;

    private Context mContext;
    private TextClassificationManager mTcm;
    private TextClassifier mClassifier;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getTargetContext();
        mTcm = mContext.getSystemService(TextClassificationManager.class);
        mClassifier = mTcm.getTextClassifier(
                mTextClassifierType.equals(LOCAL) ? TextClassifier.LOCAL : TextClassifier.SYSTEM);
    }

    @Test
    public void testSmartSelection() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String selected = "droid";
        String suggested = "droid@android.com";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        int smartStartIndex = text.indexOf(suggested);
        int smartEndIndex = smartStartIndex + suggested.length();
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
        assertThat(selection,
                isTextSelection(smartStartIndex, smartEndIndex, TextClassifier.TYPE_EMAIL));
    }

    @Test
    public void testSmartSelection_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit http://www.android.com for more information";
        String selected = "http";
        String suggested = "http://www.android.com";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        int smartStartIndex = text.indexOf(suggested);
        int smartEndIndex = smartStartIndex + suggested.length();
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
        assertThat(selection,
                isTextSelection(smartStartIndex, smartEndIndex, TextClassifier.TYPE_URL));
    }

    @Test
    public void testSmartSelection_withEmoji() {
        if (isTextClassifierDisabled()) return;

        String text = "\uD83D\uDE02 Hello.";
        String selected = "Hello";
        int startIndex = text.indexOf(selected);
        int endIndex = startIndex + selected.length();
        TextSelection.Request request = new TextSelection.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextSelection selection = mClassifier.suggestSelection(request);
        assertThat(selection,
                isTextSelection(startIndex, endIndex, NO_TYPE));
    }

    @Test
    public void testClassifyText() {
        if (isTextClassifierDisabled()) return;

        String text = "Contact me at droid@android.com";
        String classifiedText = "droid@android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_EMAIL));
    }

    @Test
    public void testTextClassifyText_url() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit www.android.com for more information";
        String classifiedText = "www.android.com";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_URL));
    }

    @Test
    public void testTextClassifyText_address() {
        if (isTextClassifierDisabled()) return;

        String text = "Brandschenkestrasse 110, Zürich, Switzerland";
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, 0, text.length())
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(text, TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testTextClassifyText_url_inCaps() {
        if (isTextClassifierDisabled()) return;

        String text = "Visit HTTP://ANDROID.COM for more information";
        String classifiedText = "HTTP://ANDROID.COM";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_URL));
    }

    @Test
    public void testTextClassifyText_date() {
        if (isTextClassifierDisabled()) return;

        String text = "Let's meet on January 9, 2018.";
        String classifiedText = "January 9, 2018";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification, isTextClassification(classifiedText, TextClassifier.TYPE_DATE));
    }

    @Test
    public void testTextClassifyText_datetime() {
        if (isTextClassifierDisabled()) return;

        String text = "Let's meet 2018/01/01 10:30:20.";
        String classifiedText = "2018/01/01 10:30:20";
        int startIndex = text.indexOf(classifiedText);
        int endIndex = startIndex + classifiedText.length();
        TextClassification.Request request = new TextClassification.Request.Builder(
                text, startIndex, endIndex)
                .setDefaultLocales(LOCALES)
                .build();

        TextClassification classification = mClassifier.classifyText(request);
        assertThat(classification,
                isTextClassification(classifiedText, TextClassifier.TYPE_DATE_TIME));
    }

    @Test
    public void testGenerateLinks_phone() {
        if (isTextClassifierDisabled()) return;
        String text = "The number is +12122537077. See you tonight!";
        TextLinks.Request request = new TextLinks.Request.Builder(text).build();
        assertThat(mClassifier.generateLinks(request),
                isTextLinksContaining(text, "+12122537077", TextClassifier.TYPE_PHONE));
    }

    @Test
    public void testGenerateLinks_exclude() {
        if (isTextClassifierDisabled()) return;
        String text = "You want apple@banana.com. See you tonight!";
        List<String> hints = Collections.EMPTY_LIST;
        List<String> included = Collections.EMPTY_LIST;
        List<String> excluded = Arrays.asList(TextClassifier.TYPE_EMAIL);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.create(hints, included, excluded))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                not(isTextLinksContaining(text, "apple@banana.com", TextClassifier.TYPE_EMAIL)));
    }

    @Test
    public void testGenerateLinks_explicit_address() {
        if (isTextClassifierDisabled()) return;
        String text = "The address is 1600 Amphitheater Parkway, Mountain View, CA. See you!";
        List<String> explicit = Arrays.asList(TextClassifier.TYPE_ADDRESS);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(explicit))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                isTextLinksContaining(text, "1600 Amphitheater Parkway, Mountain View, CA",
                        TextClassifier.TYPE_ADDRESS));
    }

    @Test
    public void testGenerateLinks_exclude_override() {
        if (isTextClassifierDisabled()) return;
        String text = "You want apple@banana.com. See you tonight!";
        List<String> hints = Collections.EMPTY_LIST;
        List<String> included = Arrays.asList(TextClassifier.TYPE_EMAIL);
        List<String> excluded = Arrays.asList(TextClassifier.TYPE_EMAIL);
        TextLinks.Request request = new TextLinks.Request.Builder(text)
                .setEntityConfig(TextClassifier.EntityConfig.create(hints, included, excluded))
                .setDefaultLocales(LOCALES)
                .build();
        assertThat(mClassifier.generateLinks(request),
                not(isTextLinksContaining(text, "apple@banana.com", TextClassifier.TYPE_EMAIL)));
    }

    @Test
    public void testGenerateLinks_maxLength() {
        if (isTextClassifierDisabled()) return;
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength()];
        Arrays.fill(manySpaces, ' ');
        TextLinks.Request request = new TextLinks.Request.Builder(new String(manySpaces)).build();
        TextLinks links = mClassifier.generateLinks(request);
        assertTrue(links.getLinks().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGenerateLinks_tooLong() {
        if (isTextClassifierDisabled()) {
            throw new IllegalArgumentException("pass if disabled");
        }
        char[] manySpaces = new char[mClassifier.getMaxGenerateLinksTextLength() + 1];
        Arrays.fill(manySpaces, ' ');
        TextLinks.Request request = new TextLinks.Request.Builder(new String(manySpaces)).build();
        mClassifier.generateLinks(request);
    }

    @Test
    public void testDetectLanguage() {
        if (isTextClassifierDisabled()) return;
        String text = "This is English text";
        TextLanguage.Request request = new TextLanguage.Request.Builder(text).build();
        TextLanguage textLanguage = mClassifier.detectLanguage(request);
        assertThat(textLanguage, isTextLanguage("en"));
    }

    @Test
    public void testDetectLanguage_japanese() {
        if (isTextClassifierDisabled()) return;
        String text = "これは日本語のテキストです";
        TextLanguage.Request request = new TextLanguage.Request.Builder(text).build();
        TextLanguage textLanguage = mClassifier.detectLanguage(request);
        assertThat(textLanguage, isTextLanguage("ja"));
    }

    @Test
    public void testSuggestConversationActions_textReplyOnly_maxThree() {
        if (isTextClassifierDisabled()) return;
        ConversationActions.Message message =
                new ConversationActions.Message.Builder().setText("Hello").build();
        ConversationActions.TypeConfig typeConfig =
                new ConversationActions.TypeConfig.Builder().includeTypesFromTextClassifier(false)
                        .setIncludedTypes(
                                Collections.singletonList(ConversationActions.TYPE_TEXT_REPLY))
                        .build();
        ConversationActions.Request request =
                new ConversationActions.Request.Builder(Collections.singletonList(message))
                        .setMaxSuggestions(3)
                        .setTypeConfig(typeConfig)
                        .build();

        ConversationActions conversationActions = mClassifier.suggestConversationActions(request);
        assertTrue(conversationActions.getConversationActions().size() > 0);
        assertTrue(conversationActions.getConversationActions().size() <= 3);
        for (ConversationActions.ConversationAction conversationAction :
                conversationActions.getConversationActions()) {
            assertEquals(conversationAction.getType(), ConversationActions.TYPE_TEXT_REPLY);
            assertNotNull(conversationAction.getTextReply());
            assertTrue(conversationAction.getConfidenceScore() > 0);
            assertTrue(conversationAction.getConfidenceScore() <= 1);
        }
    }


    private boolean isTextClassifierDisabled() {
        return mClassifier == null || mClassifier == TextClassifier.NO_OP;
    }

    private static Matcher<TextSelection> isTextSelection(
            final int startIndex, final int endIndex, final String type) {
        return new BaseMatcher<TextSelection>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextSelection) {
                    TextSelection selection = (TextSelection) o;
                    return startIndex == selection.getSelectionStartIndex()
                            && endIndex == selection.getSelectionEndIndex()
                            && typeMatches(selection, type);
                }
                return false;
            }

            private boolean typeMatches(TextSelection selection, String type) {
                return type == null
                        || (selection.getEntityCount() > 0
                        && type.trim().equalsIgnoreCase(selection.getEntity(0)));
            }

            @Override
            public void describeTo(Description description) {
                description.appendValue(
                        String.format("%d, %d, %s", startIndex, endIndex, type));
            }
        };
    }

    private static Matcher<TextLinks> isTextLinksContaining(
            final String text, final String substring, final String type) {
        return new BaseMatcher<TextLinks>() {

            @Override
            public void describeTo(Description description) {
                description.appendText("text=").appendValue(text)
                        .appendText(", substring=").appendValue(substring)
                        .appendText(", type=").appendValue(type);
            }

            @Override
            public boolean matches(Object o) {
                if (o instanceof TextLinks) {
                    for (TextLinks.TextLink link : ((TextLinks) o).getLinks()) {
                        if (text.subSequence(link.getStart(), link.getEnd()).equals(substring)) {
                            return type.equals(link.getEntity(0));
                        }
                    }
                }
                return false;
            }
        };
    }

    private static Matcher<TextClassification> isTextClassification(
            final String text, final String type) {
        return new BaseMatcher<TextClassification>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextClassification) {
                    TextClassification result = (TextClassification) o;
                    return text.equals(result.getText())
                            && result.getEntityCount() > 0
                            && type.equals(result.getEntity(0));
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("text=").appendValue(text)
                        .appendText(", type=").appendValue(type);
            }
        };
    }

    private static Matcher<TextLanguage> isTextLanguage(final String languageTag) {
        return new BaseMatcher<TextLanguage>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof TextLanguage) {
                    TextLanguage result = (TextLanguage) o;
                    return result.getLocaleHypothesisCount() > 0
                            && languageTag.equals(result.getLocale(0).toLanguageTag());
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("locale=").appendValue(languageTag);
            }
        };
    }
}