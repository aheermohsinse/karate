/*
 * The MIT License
 *
 * Copyright 2017 Intuit Inc.
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
package com.intuit.karate.cucumber;

import com.intuit.karate.CallContext;
import com.intuit.karate.exception.KarateException;
import com.intuit.karate.ScriptEnv;
import com.intuit.karate.ScriptValueMap;
import com.intuit.karate.StringUtils;
import cucumber.runtime.AmbiguousStepDefinitionsException;
import cucumber.runtime.FeatureBuilder;
import cucumber.runtime.RuntimeGlue;
import cucumber.runtime.StepDefinitionMatch;
import cucumber.runtime.UndefinedStepsTracker;
import cucumber.runtime.model.CucumberFeature;
import cucumber.runtime.xstream.LocalizedXStreams;
import gherkin.I18n;
import gherkin.formatter.Reporter;
import gherkin.formatter.model.Match;
import gherkin.formatter.model.Result;
import gherkin.formatter.model.Scenario;
import gherkin.formatter.model.Step;
import gherkin.formatter.model.Tag;
import gherkin.parser.Parser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author pthomas3
 */
public class CucumberUtils {

    private CucumberUtils() {
        // only static methods
    }   
    
    public static void resolveTagsAndTagValues(KarateBackend backend, Set<Tag> tags) {
        if (tags.isEmpty()) {
            backend.setTagValues(Collections.emptyMap());
            backend.setTags(Collections.emptyList());
            return;
        }
        Map<String, List<String>> tagValues = new LinkedHashMap(tags.size());
        Map<String, Integer> tagKeyLines = new HashMap(tags.size());
        List<String> rawTags = new ArrayList(tags.size());
        for (Tag tag : tags) {
            Integer line = tag.getLine();
            String name = tag.getName();
            List<String> values = new ArrayList();
            if (name.startsWith("@")) {
                name = name.substring(1);
            }
            rawTags.add(name);
            Integer prevTagLine = tagKeyLines.get(name);
            if (prevTagLine != null && prevTagLine > line) {
                continue; // skip tag with same name but lower line number, 
            }
            tagKeyLines.put(name, line);
            int pos = name.indexOf('=');
            if (pos != -1) {
                if (name.length() == pos + 1) { // edge case, @foo=
                    values.add("");
                } else {
                    String temp = name.substring(pos + 1);
                    for (String s : temp.split(",")) {
                        values.add(s);
                    }
                }
                name = name.substring(0, pos);
            }
            tagValues.put(name, values);
        }
        backend.setTagValues(tagValues);
        backend.setTags(rawTags);        
    }
    
    public static void initScenarioInfo(Scenario scenario, KarateBackend backend) {
        ScenarioInfo info = new ScenarioInfo();
        ScriptEnv env = backend.getEnv();
        info.setFeatureDir(env.featureDir.getPath());
        info.setFeatureFileName(env.featureName);
        info.setScenarioName(scenario.getName());
        info.setScenarioType(scenario.getKeyword()); // 'Scenario' | 'Scenario Outline'
        info.setScenarioDescription(scenario.getDescription());
        backend.setScenarioInfo(info);        
    }

    public static KarateBackend getBackendWithGlue(ScriptEnv env, CallContext callContext) {
        KarateBackend backend = new KarateBackend(env, callContext);
        ClassLoader defaultClassLoader = Thread.currentThread().getContextClassLoader();
        RuntimeGlue glue = new RuntimeGlue(new UndefinedStepsTracker(), new LocalizedXStreams(defaultClassLoader));
        backend.loadGlue(glue, null);
        return backend;
    }
    
    public static CucumberFeature parse(String text, String featurePath) {
        final List<CucumberFeature> features = new ArrayList<>();
        final FeatureBuilder builder = new FeatureBuilder(features);
        Parser parser = new Parser(builder);
        parser.parse(text, featurePath, 0);
        CucumberFeature cucumberFeature = features.get(0);
        cucumberFeature.setI18n(parser.getI18nLanguage());
        return cucumberFeature;
    }

    public static ScriptValueMap call(FeatureWrapper feature, CallContext callContext) {
        ScriptEnv env = feature.getEnv();
        KarateBackend backend = getBackendWithGlue(env, callContext);
        for (FeatureSection section : feature.getSections()) {
            if (section.isOutline()) {
                ScenarioOutlineWrapper outline = section.getScenarioOutline();
                for (ScenarioWrapper scenario : outline.getScenarios()) {
                    call(scenario, backend);
                }
            } else {
                call(section.getScenario(), backend);
            }
        }
        
        return backend.getStepDefs().getContext().getVars();
    }

    public static void call(ScenarioWrapper scenario, KarateBackend backend) {
        for (StepWrapper step : scenario.getSteps()) {
            StepResult result = runCalledStep(step, backend);
            if (!result.isPass()) {
                FeatureWrapper feature = scenario.getFeature();
                String scenarioName = StringUtils.trimToNull(scenario.getScenario().getGherkinModel().getName());
                String message = "called: " + feature.getPath();
                if (scenarioName != null) {
                    message = message + ", scenario: " + scenarioName;
                }
                message = message + ", line: " + step.getStep().getLine();
                throw new KarateException(message, result.getError());
            }
        }
    }
    
    public static StepResult runCalledStep(StepWrapper step, KarateBackend backend) {
        FeatureWrapper wrapper = step.getScenario().getFeature();
        CucumberFeature feature = wrapper.getFeature();
        return runStep(wrapper.getPath(), step.getStep(), backend.getEnv().reporter, feature.getI18n(), backend);
    }    
    
    private static final DummyReporter DUMMY_REPORTER = new DummyReporter();
    
    // adapted from cucumber.runtime.Runtime.runCalledStep
    public static StepResult runStep(String featurePath, Step step, Reporter reporter, I18n i18n, 
            KarateBackend backend) {
        backend.beforeStep(featurePath, step);
        if (reporter == null) {
            reporter = DUMMY_REPORTER;
        }      
        StepDefinitionMatch match;
        try {
            match = backend.getGlue().stepDefinitionMatch(featurePath, step, i18n);
        } catch (AmbiguousStepDefinitionsException e) {
            match = e.getMatches().get(0);
            Result result = new Result(Result.FAILED, 0L, e, KarateReporterBase.DUMMY_OBJECT);
            return afterStep(reporter, step, match, result, e, featurePath, backend);
        }
        if (match == null) {
            return afterStep(reporter, step, Match.UNDEFINED, Result.UNDEFINED, 
                    new KarateException("syntax error: " + step.getName()),
                    featurePath, backend);
        }
        String status = Result.PASSED;
        Throwable error = null;
        long startTime = System.nanoTime();
        try {            
            match.runStep(i18n);
        } catch (Throwable t) {
            error = t;
            status = Result.FAILED;
        } finally {
            long duration = backend.isCalled() ? 0 : System.nanoTime() - startTime;
            Result result = new Result(status, duration, error, KarateReporterBase.DUMMY_OBJECT);
            return afterStep(reporter, step, match, result, error, featurePath, backend);
        }        
    }
    
    private static StepResult afterStep(Reporter reporter, Step step, Match match, Result result, 
            Throwable error, String feature, KarateBackend backend) {
        boolean isKarateReporter = reporter instanceof KarateReporter;
        CallContext callContext = backend.getCallContext();
        if (isKarateReporter) { // report all the things !           
            KarateReporter karateReporter = (KarateReporter) reporter;
            karateReporter.karateStep(step, match, result, callContext);
        } else if (!backend.isCalled()) { // cucumber native reporter, steps would have been set upfront
            // preserve normal life-cycle
            reporter.match(match);
            reporter.result(result);            
        }
        StepResult stepResult = new StepResult(step, result, error);
        backend.afterStep(feature, stepResult);
        return stepResult;
    }

}
