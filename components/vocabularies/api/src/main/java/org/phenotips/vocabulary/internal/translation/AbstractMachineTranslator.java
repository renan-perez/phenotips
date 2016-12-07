/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.vocabulary.internal.translation;

import org.phenotips.vocabulary.MachineTranslator;
import org.phenotips.vocabulary.VocabularyInputTerm;
import org.phenotips.vocabulary.VocabularyTerm;

import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.environment.Environment;
import org.xwiki.localization.LocalizationContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

/**
 * Base class for any machine translator implementations.
 *
 * @version $Id$
 */
public abstract class AbstractMachineTranslator implements MachineTranslator, Initializable
{
    /**
     * The name of the directory containing the translation files.
     */
    protected static final String HOME_DIR_NAME = "vocabulary_translations";

    /**
     * The current environment.
     */
    @Inject
    private Environment environment;

    /**
     * The current localization context.
     */
    @Inject
    private LocalizationContext localizationContext;

    /**
     * Our logger.
     */
    @Inject
    private Logger logger;

    /**
     * The home directory.
     */
    private File home;

    /**
     * The loaded translations.
     */
    private XLiffMap translations = new XLiffMap();

    /**
     * The xml mapper.
     */
    private XmlMapper mapper = new XmlMapper();

    @Override
    public void initialize() throws InitializationException
    {
        this.home = this.environment.getPermanentDirectory();
        this.home = new File(this.home, HOME_DIR_NAME);
        this.home = new File(this.home, getIdentifier());
        if (!this.home.exists()) {
            this.home.mkdirs();
            for (String vocabulary : getSupportedVocabularies()) {
                for (String language : getSupportedLanguages()) {
                    String name = getFileName(vocabulary, language);
                    InputStream resource = this.getClass().getResourceAsStream(name);
                    try {
                        OutputStream out = new FileOutputStream(new File(this.home, name));
                        IOUtils.copy(resource, out);
                        out.close();
                        resource.close();
                    } catch (IOException e) {
                        /* This is bad, prevent the component from working at all */
                        throw new InitializationException(e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Get the name of the translation file for the vocabulary and language given.
     *
     * @param vocabulary the vocabulary
     * @param language the language
     * @return the translation file name
     */
    protected String getFileName(String vocabulary, String language)
    {
        return String.format("%s_%s_%s.xliff", getIdentifier(), vocabulary, language);
    }

    /**
     * Get an identifier for this machine translator.
     *
     * @return the identifier
     */
    protected abstract String getIdentifier();

    /**
     * Get the home directory for the machine translating component.
     *
     * @return the home directory
     */
    protected File getHomeDir()
    {
        return this.home;
    }

    @Override
    public boolean loadVocabulary(String vocabulary, String lang)
    {
        File file = new File(this.home, getFileName(vocabulary, lang));
        try {
            XLiffFile xliff = this.mapper.readValue(file, XLiffFile.class);
            this.translations.put(vocabulary, lang, xliff);
        } catch (IOException e) {
            this.logger.error(String.format("Could not load %s: %s", file.toString(), e.getMessage()));
            return false;
        }
        return true;
    }

    @Override
    public boolean unloadVocabulary(String vocabulary, String lang)
    {
        XLiffFile xliff = this.translations.remove(vocabulary, lang);
        File file = new File(this.home, getFileName(vocabulary, lang));
        try {
            this.mapper.writeValue(file, xliff);
        } catch (IOException e) {
            this.logger.error(String.format("Threw on writing %s: %s", file.toString(),
                e.getMessage()));
            return false;
        }
        return true;
    }

    @Override
    public long getMissingCharacters(String vocabulary, String lang, VocabularyTerm term,
        Collection<String> fields)
    {
        long count = 0;
        XLiffFile xliff = this.translations.get(vocabulary, lang);
        for (String field : fields) {
            if (xliff.getFirstString(term.getId(), field) == null) {
                Object o = term.get(field);
                if (o instanceof String) {
                    count += ((String) o).length();
                } else if (o instanceof Iterable) {
                    Iterable<Object> objects = (Iterable) o;
                    for (Object inner : objects) {
                        if (inner instanceof String) {
                            count += ((String) inner).length();
                        }
                    }
                }
            }
        }
        return count;
    }

    @Override
    public long translate(String vocabulary, String lang, VocabularyInputTerm term,
        Collection<String> fields)
    {
        long count = 0;
        XLiffFile xliff = this.translations.get(vocabulary, lang);
        for (String field : fields) {
            String translatedField = field + "_" + lang;
            Object o = term.get(field);
            if (o instanceof String) {
                String there = xliff.getFirstString(term.getId(), field);
                if (there != null) {
                    term.set(translatedField, there);
                } else {
                    String string = (String) o;
                    count += string.length();
                    String result = doTranslate(string, lang);
                    if (result != null) {
                        term.set(translatedField, result);
                        xliff.setString(term.getId(), field, string, result);
                    }
                }
            } else if (o instanceof Iterable) {
                Iterable<Object> objects = (Iterable) o;
                List<String> there = xliff.getStrings(term.getId(), field);
                if (there != null) {
                    term.set(translatedField, there);
                } else {
                    count += translateIterable(objects, lang, term, field, translatedField, xliff);
                }
            }
        }
        return count;
    }

    /**
     * Translate an iterable field.
     *
     * @param objects the iterable
     * @param lang the target language
     * @param term the vocabulary term
     * @param field the field we're translating
     * @param translatedField the translated name of the field
     * @param xliff the xliff file to store translations in.
     */
    private long translateIterable(Iterable<Object> objects, String lang, VocabularyInputTerm term,
        String field, String translatedField, XLiffFile xliff)
    {
        long count = 0;
        for (Object inner : objects) {
            if (inner instanceof String) {
                String string = (String) inner;
                count += string.length();
                String result = doTranslate(string, lang);
                if (result != null) {
                    term.append(translatedField, result);
                    xliff.appendString(term.getId(), field, string, result);
                }
            } else {
                /* Not a string, append it as it is */
                term.append(translatedField, inner);
            }
        }
        return count;
    }

    /**
     * Actually run the input given through a machine translation. It's possible to return null here in which case no
     * translation will be saved. This allows for graceful failing of the translator.
     *
     * @param input the input
     * @param lang the language
     * @return the translated string
     */
    protected abstract String doTranslate(String input, String lang);
}
