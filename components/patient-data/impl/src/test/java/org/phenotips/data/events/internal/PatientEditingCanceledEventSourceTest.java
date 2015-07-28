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
package org.phenotips.data.events.internal;

import org.phenotips.data.Patient;
import org.phenotips.data.PatientRepository;
import org.phenotips.data.events.PatientEditingCanceledEvent;
import org.phenotips.data.events.PatientEvent;

import org.xwiki.bridge.event.ActionExecutedEvent;
import org.xwiki.bridge.event.ActionExecutingEvent;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentDeletingEvent;
import org.xwiki.bridge.event.DocumentUpdatingEvent;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.ObservationManager;
import org.xwiki.observation.event.Event;
import org.xwiki.test.mockito.MockitoComponentMockingRule;
import org.xwiki.users.User;
import org.xwiki.users.UserManager;

import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.internal.matchers.CapturingMatcher;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PatientEditingCanceledEventSourceTest
{
    @Rule
    public final MockitoComponentMockingRule<EventListener> mocker =
        new MockitoComponentMockingRule<EventListener>(PatientEditingCanceledEventSource.class);

    private DocumentReference ref = new DocumentReference("instance", "data", "P0000001");

    String CANCEL = "cancel";

    @Test
    public void hasProperName() throws ComponentLookupException
    {
        Assert.assertEquals("patientEditingCanceledEventSource", this.mocker.getComponentUnderTest().getName());
    }

    /**
     * Making sure that the action that is listened to is in progress, and has not already occurred.
     * @throws ComponentLookupException
     */
    @Test
    public void listensForCancelActionBeforeFinalization() throws ComponentLookupException
    {
        List<Event> events = this.mocker.getComponentUnderTest().getEvents();
        Assert.assertFalse(events.isEmpty());
        Assert.assertEquals(1, events.size());
        Assert.assertTrue(events.iterator().next().matches(new ActionExecutingEvent(CANCEL)));
        Assert.assertFalse(events.iterator().next().matches(new ActionExecutedEvent(CANCEL)));
        Assert.assertFalse(events.iterator().next().matches(new ActionExecutingEvent("edit")));
        Assert.assertFalse(events.iterator().next().matches(new DocumentDeletingEvent()));
        Assert.assertFalse(events.iterator().next().matches(new DocumentDeletedEvent()));
        Assert.assertFalse(events.iterator().next().matches(new DocumentCreatingEvent()));
        Assert.assertFalse(events.iterator().next().matches(new DocumentUpdatingEvent()));
    }

    @Test
    public void firesPatientEditingCanceledEventsWhenEditingIsCanceled() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(this.ref);
        when(doc.getXObject(Patient.CLASS_REFERENCE)).thenReturn(mock(BaseObject.class));

        PatientRepository repo = this.mocker.getInstance(PatientRepository.class);
        Patient p = mock(Patient.class);
        when(repo.loadPatientFromDocument(doc)).thenReturn(p);

        UserManager um = this.mocker.getInstance(UserManager.class);
        User u = mock(User.class);
        when(um.getCurrentUser()).thenReturn(u);

        ObservationManager om = this.mocker.getInstance(ObservationManager.class);
        CapturingMatcher<PatientEvent> evtCapture = new CapturingMatcher<>();
        CapturingMatcher<Object> dataCapture = new CapturingMatcher<>();
        Mockito.doNothing().when(om).notify(Matchers.argThat(evtCapture), Matchers.argThat(dataCapture));

        this.mocker.getComponentUnderTest().onEvent(new ActionExecutingEvent(CANCEL), doc, null);

        Assert.assertEquals(1, evtCapture.getAllValues().size());
        PatientEvent evt = evtCapture.getLastValue();
        Assert.assertNotNull(evt);
        Assert.assertTrue(evt instanceof PatientEditingCanceledEvent);
        Assert.assertSame(u, evt.getAuthor());
        Assert.assertSame(p, evt.getPatient());
        Assert.assertSame(doc, dataCapture.getLastValue());
    }

    @Test
    public void doesntFireWhenNonPatientDocumentsEditActionsAreCanceled() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(this.ref);
        when(doc.getXObject(Patient.CLASS_REFERENCE)).thenReturn(null);

        PatientRepository repo = this.mocker.getInstance(PatientRepository.class);
        UserManager um = this.mocker.getInstance(UserManager.class);
        ObservationManager om = this.mocker.getInstance(ObservationManager.class);

        this.mocker.getComponentUnderTest().onEvent(new ActionExecutingEvent(CANCEL), doc, null);
        Mockito.verifyNoMoreInteractions(repo, um, om);
    }

    @Test
    public void doesntFireWhenPatientTemplateEditActionsAreCanceled() throws ComponentLookupException
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(doc.getDocumentReference()).thenReturn(new DocumentReference("instance", "PhenoTips", "PatientTemplate"));

        PatientRepository repo = this.mocker.getInstance(PatientRepository.class);
        UserManager um = this.mocker.getInstance(UserManager.class);
        ObservationManager om = this.mocker.getInstance(ObservationManager.class);

        this.mocker.getComponentUnderTest().onEvent(new ActionExecutingEvent(CANCEL), doc, null);
        Mockito.verifyNoMoreInteractions(repo, um, om);
    }
}