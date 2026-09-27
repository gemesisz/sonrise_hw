'use strict';

// Admin page: a thin client of /api/admin. No logic lives here that the API doesn't enforce.
//
// Security rules for this file:
//  - Data (event titles from RSS feeds, user names, error messages) is only ever put into the
//    page with textContent via el(), never innerHTML: feed content is untrusted.
//  - Links from feeds are only rendered for http(s) URLs (no javascript: URLs).
//  - Every write sends the CSRF token from the XSRF-TOKEN cookie as the X-XSRF-TOKEN header.

const API = '/api/admin';
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const PAGE_SIZE = 20;

const state = {
    categories: [],
    channels: [],
    eventPage: 0,
    notificationPage: 0,
};

// ---------------------------------------------------------------- helpers

/** Creates an element; strings in children become text nodes, never HTML. */
function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (value === undefined || value === null || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else if (key in node && typeof value !== 'string') node[key] = value;
        else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

function badge(value) {
    return el('span', { class: `badge ${value}` }, value);
}

function formatTime(iso) {
    return iso ? new Date(iso).toLocaleString() : '—';
}

function formatDuration(iso) {
    // ISO-8601 durations as sent by the API, e.g. PT5M, PT15S, PT1H30M.
    const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso || '');
    if (!match) return iso || '—';
    const [, h, m, s] = match;
    return [h && `${h}h`, m && `${m}m`, s && `${s}s`].filter(Boolean).join(' ') || '0s';
}

/** Only http(s) links from feeds become clickable. */
function safeLink(url, text) {
    try {
        const parsed = new URL(url);
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
            return el('a', { href: parsed.href, target: '_blank', rel: 'noopener noreferrer' }, text);
        }
    } catch { /* not a URL */ }
    return document.createTextNode(text);
}

function csrfToken() {
    const cookie = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
    return cookie ? decodeURIComponent(cookie.substring('XSRF-TOKEN='.length)) : '';
}

class ApiError extends Error {
    constructor(status, problem) {
        super(problem?.detail || problem?.title || `Request failed (${status})`);
        this.status = status;
        this.errors = problem?.errors || {};
    }
}

async function api(method, path, body) {
    const headers = { Accept: 'application/json' };
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(API + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
    });
    if (response.status === 204) return null;
    const data = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError(response.status, data);
    return data;
}

let toastTimer;
function toast(message, isError = false) {
    const box = document.getElementById('toast');
    box.textContent = message;
    box.classList.toggle('error', isError);
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { box.hidden = true; }, isError ? 8000 : 3000);
}

/** Shows an API error: field errors next to their inputs, everything else as a toast. */
function showError(error, form) {
    if (form) {
        form.querySelectorAll('.field-error').forEach(span => { span.textContent = ''; });
        let shown = false;
        for (const [field, message] of Object.entries(error.errors || {})) {
            const span = form.querySelector(`.field-error[data-for="${CSS.escape(field)}"]`);
            if (span) {
                span.textContent = message;
                shown = true;
            }
        }
        if (shown) return;
    }
    toast(error.message, true);
}

function clearErrors(form) {
    form?.querySelectorAll('.field-error').forEach(span => { span.textContent = ''; });
}

/** Runs an API action with the button disabled; reports errors. */
async function run(button, action, form) {
    if (button) button.disabled = true;
    try {
        clearErrors(form);
        return await action();
    } catch (error) {
        showError(error, form);
        return undefined;
    } finally {
        if (button) button.disabled = false;
    }
}

function options(values, selected) {
    return values.map(v => {
        const [value, label] = Array.isArray(v) ? v : [v, v];
        return el('option', { value, selected: value === selected }, label);
    });
}

function pager(container, page, onChange) {
    container.replaceChildren(
        el('span', {}, page.totalItems === 0 ? 'No results' :
            `Page ${page.page + 1} of ${Math.max(page.totalPages, 1)} · ${page.totalItems} total`),
        el('button', { type: 'button', class: 'secondary', disabled: page.page <= 0,
            onclick: () => onChange(page.page - 1) }, 'Previous'),
        el('button', { type: 'button', class: 'secondary', disabled: page.page + 1 >= page.totalPages,
            onclick: () => onChange(page.page + 1) }, 'Next'),
    );
}

// ---------------------------------------------------------------- tabs

const loaders = {
    users: loadUsers,
    events: () => loadEvents(state.eventPage),
    notifications: () => loadNotifications(state.notificationPage),
    detection: loadSources,
    channels: loadChannels,
};

function showTab(name) {
    document.querySelectorAll('.tabs button').forEach(button => {
        button.setAttribute('aria-selected', String(button.dataset.tab === name));
    });
    document.querySelectorAll('.tab').forEach(section => {
        section.hidden = section.id !== `tab-${name}`;
    });
    history.replaceState(null, '', `#${name}`);
    loaders[name]().catch(error => showError(error));
}

// ---------------------------------------------------------------- users

async function loadUsers() {
    const users = await api('GET', '/users');
    const list = document.getElementById('user-list');
    list.replaceChildren(...(users.length ? users.map(renderUser) : [el('p', { class: 'empty' }, 'No users yet.')]));
}

function renderUser(user) {
    const open = document.querySelector(`details.user[data-id="${user.id}"]`)?.open ?? false;
    const summary = el('summary', {},
        el('span', { class: 'name' }, user.name),
        el('span', { class: 'meta' },
            `${user.subscriptions.length} subscription(s) · ${user.channels.length} channel(s) · since ${formatTime(user.createdAt)}`));
    return el('details', { class: 'user card', dataset: { id: user.id }, open },
        summary,
        el('div', { class: 'user-body' },
            renderSubscriptions(user),
            renderChannelLinks(user),
            renderUserActions(user)));
}

function renderSubscriptions(user) {
    const rows = user.subscriptions.map(sub => el('div', { class: 'row' },
        el('span', { class: 'address' }, categoryName(sub.category)),
        el('label', {}, 'Min severity',
            el('select', { onchange: e => saveSubscription(user.id, sub.category, e.target.value, e.target) },
                options(SEVERITIES, sub.minSeverity))),
        el('button', { type: 'button', class: 'danger',
            onclick: e => run(e.target, async () => {
                await api('DELETE', `/users/${user.id}/subscriptions/${encodeURIComponent(sub.category)}`);
                await loadUsers();
            }) }, 'Remove')));

    const subscribed = new Set(user.subscriptions.map(s => s.category));
    const available = state.categories.filter(c => !subscribed.has(c.code));
    const form = el('form', { class: 'inline-form', novalidate: true },
        el('label', {}, 'Category', el('select', { name: 'category' }, options(available.map(c => [c.code, c.name])))),
        el('label', {}, 'Min severity', el('select', { name: 'minSeverity' }, options(SEVERITIES, 'MEDIUM'))),
        el('button', { type: 'submit' }, 'Subscribe'),
        el('span', { class: 'field-error', dataset: { for: 'minSeverity' } }));
    form.addEventListener('submit', event => {
        event.preventDefault();
        const data = new FormData(form);
        saveSubscription(user.id, data.get('category'), data.get('minSeverity'), form.querySelector('button'), form);
    });

    return el('div', {},
        el('h3', {}, 'Subscriptions'),
        el('div', { class: 'rows' }, rows.length ? rows : el('span', { class: 'empty' }, 'Not subscribed to anything.')),
        available.length ? form : null);
}

function saveSubscription(userId, category, minSeverity, button, form) {
    return run(button, async () => {
        await api('PUT', `/users/${userId}/subscriptions/${encodeURIComponent(category)}`, { minSeverity });
        await loadUsers();
        toast('Subscription saved');
    }, form);
}

function renderChannelLinks(user) {
    const rows = user.channels.map(link => el('div', { class: 'row' },
        el('strong', {}, channelName(link.channel)),
        el('span', { class: 'address' }, link.address),
        el('label', {}, 'Enabled',
            el('input', { type: 'checkbox', checked: link.enabled,
                onchange: e => run(e.target, async () => {
                    // PATCH, not PUT: the page only has the masked Slack URL, never the real one.
                    await api('PATCH', `/users/${user.id}/channels/${encodeURIComponent(link.channel)}`,
                        { enabled: e.target.checked });
                    await loadUsers();
                }) })),
        el('button', { type: 'button', class: 'danger',
            onclick: e => run(e.target, async () => {
                await api('DELETE', `/users/${user.id}/channels/${encodeURIComponent(link.channel)}`);
                await loadUsers();
            }) }, 'Remove')));

    const form = el('form', { class: 'inline-form', novalidate: true },
        el('label', {}, 'Channel', el('select', { name: 'channel' }, options(state.channels.map(c => [c.code, c.name])))),
        el('label', {}, 'Address', el('input', { name: 'address', maxlength: 500, required: true,
            placeholder: 'email address or Slack webhook URL' })),
        el('button', { type: 'submit' }, 'Save channel'),
        el('span', { class: 'field-error', dataset: { for: 'address' } }));
    form.addEventListener('submit', event => {
        event.preventDefault();
        const data = new FormData(form);
        run(form.querySelector('button'), async () => {
            await api('PUT', `/users/${user.id}/channels/${encodeURIComponent(data.get('channel'))}`,
                { address: data.get('address') });
            await loadUsers();
            toast('Channel saved');
        }, form);
    });

    return el('div', {},
        el('h3', {}, 'Channels'),
        el('div', { class: 'rows' }, rows.length ? rows : el('span', { class: 'empty' }, 'No channels: this user gets nothing.')),
        form,
        el('p', { class: 'hint' }, 'Saving an existing channel replaces its address. Slack webhook URLs are shown masked.'));
}

function renderUserActions(user) {
    const rename = el('form', { class: 'inline-form', novalidate: true },
        el('label', {}, 'Name', el('input', { name: 'name', value: user.name, maxlength: 100, required: true })),
        el('button', { type: 'submit', class: 'secondary' }, 'Rename'),
        el('span', { class: 'field-error', dataset: { for: 'name' } }));
    rename.addEventListener('submit', event => {
        event.preventDefault();
        run(rename.querySelector('button'), async () => {
            await api('PUT', `/users/${user.id}`, { name: new FormData(rename).get('name') });
            await loadUsers();
            toast('User renamed');
        }, rename);
    });

    const remove = el('button', { type: 'button', class: 'danger', onclick: e => {
        if (!confirm(`Delete ${user.name}? Their subscriptions, channels and notification history are deleted too.`)) return;
        run(e.target, async () => {
            await api('DELETE', `/users/${user.id}`);
            await loadUsers();
            toast('User deleted');
        });
    } }, 'Delete user');

    return el('div', { class: 'user-actions' }, rename, remove);
}

function categoryName(code) {
    return state.categories.find(c => c.code === code)?.name || code;
}

function channelName(code) {
    return state.channels.find(c => c.code === code)?.name || code;
}

// ---------------------------------------------------------------- events

async function loadEvents(page) {
    const filters = new FormData(document.getElementById('event-filters'));
    const params = new URLSearchParams({ page, size: PAGE_SIZE });
    for (const [key, value] of filters) if (value) params.set(key, value);
    const result = await api('GET', `/events?${params}`);
    state.eventPage = result.page;
    document.getElementById('event-rows').replaceChildren(...(result.items.length
        ? result.items.map(e => el('tr', {},
            el('td', { class: 'nowrap' }, formatTime(e.detectedAt)),
            el('td', {}, badge(e.severity)),
            el('td', {}, categoryName(e.category)),
            el('td', {}, e.source),
            el('td', {}, e.url ? safeLink(e.url, e.title) : e.title,
                e.description ? el('div', { class: 'hint' }, e.description) : null),
            el('td', { class: 'nowrap' }, formatTime(e.occurredAt))))
        : [el('tr', {}, el('td', { colspan: 6, class: 'empty' }, 'No events.'))]));
    pager(document.getElementById('event-pager'), result, p => loadEvents(p).catch(showError));
}

// ---------------------------------------------------------------- notifications

async function loadNotifications(page) {
    const filters = new FormData(document.getElementById('notification-filters'));
    const params = new URLSearchParams({ page, size: PAGE_SIZE });
    for (const [key, value] of filters) if (value) params.set(key, value);
    const result = await api('GET', `/notifications?${params}`);
    state.notificationPage = result.page;
    document.getElementById('notification-rows').replaceChildren(...(result.items.length
        ? result.items.map(n => el('tr', {},
            el('td', { class: 'nowrap' }, formatTime(n.createdAt)),
            el('td', {}, badge(n.status)),
            el('td', {}, n.userName),
            el('td', {}, channelName(n.channel)),
            el('td', {}, n.eventTitle),
            el('td', {}, String(n.attempts)),
            el('td', { class: 'nowrap' }, n.nextAttemptAt ? formatTime(n.nextAttemptAt) : '—'),
            el('td', { class: 'error' }, n.lastError || ''),
            el('td', {}, n.status === 'FAILED'
                ? el('button', { type: 'button', class: 'secondary', onclick: e => run(e.target, async () => {
                    const updated = await api('POST', `/notifications/${n.id}/retry`);
                    toast(updated.status === 'SENT' ? 'Sent' : `Still failing: ${updated.lastError}`, updated.status !== 'SENT');
                    await loadNotifications(state.notificationPage);
                }) }, 'Retry now')
                : null)))
        : [el('tr', {}, el('td', { colspan: 9, class: 'empty' }, 'No notifications.'))]));
    pager(document.getElementById('notification-pager'), result, p => loadNotifications(p).catch(showError));
}

// ---------------------------------------------------------------- detection

const lastManualRun = new Map();

async function loadSources() {
    const sources = await api('GET', '/sources');
    fillSourceFilter(sources);
    document.getElementById('source-rows').replaceChildren(...sources.map(s => {
        const run = lastManualRun.get(s.code);
        return el('tr', {},
            el('td', {}, s.code),
            el('td', {}, s.enabled ? 'yes' : 'no'),
            el('td', {}, formatDuration(s.interval)),
            el('td', { class: 'nowrap' }, formatTime(s.lastStartedAt)),
            el('td', {}, run ? renderRun(run) : '—'));
    }));
}

function renderRun(run) {
    if (run.status !== 'COMPLETED') {
        return el('span', {}, badge(run.status), run.error ? ` ${run.error}` : '');
    }
    return el('span', {}, badge(run.status),
        ` found ${run.found}, new ${run.created}, duplicates ${run.duplicates}` + (run.rejected ? `, rejected ${run.rejected}` : ''));
}

function fillSourceFilter(sources) {
    const select = document.querySelector('#event-filters select[name="source"]');
    const current = select.value;
    select.replaceChildren(el('option', { value: '' }, 'Any'), ...options(sources.map(s => s.code), current));
}

// ---------------------------------------------------------------- channels

async function loadChannels() {
    state.channels = await api('GET', '/channels');
    document.getElementById('channel-rows').replaceChildren(...state.channels.map(c => el('tr', {},
        el('td', {}, c.name),
        el('td', {}, c.code),
        el('td', {}, el('input', { type: 'checkbox', checked: c.enabled, 'aria-label': `${c.name} enabled`,
            onchange: e => run(e.target, async () => {
                await api('PATCH', `/channels/${encodeURIComponent(c.code)}`, { enabled: e.target.checked });
                await loadChannels();
                toast(`${c.name} ${e.target.checked ? 'enabled' : 'disabled'}`);
            }) })))));
}

// ---------------------------------------------------------------- start

async function init() {
    document.querySelectorAll('.tabs button').forEach(button => {
        button.addEventListener('click', () => showTab(button.dataset.tab));
    });

    const createUser = document.getElementById('create-user');
    createUser.addEventListener('submit', event => {
        event.preventDefault();
        run(createUser.querySelector('button'), async () => {
            await api('POST', '/users', { name: new FormData(createUser).get('name') });
            createUser.reset();
            await loadUsers();
            toast('User added');
        }, createUser);
    });

    document.getElementById('event-filters').addEventListener('submit', event => {
        event.preventDefault();
        loadEvents(0).catch(error => showError(error));
    });
    document.getElementById('notification-filters').addEventListener('submit', event => {
        event.preventDefault();
        loadNotifications(0).catch(error => showError(error));
    });

    document.getElementById('run-now').addEventListener('click', event => run(event.target, async () => {
        const runs = await api('POST', '/detection/run');
        runs.forEach(r => lastManualRun.set(r.source, r));
        await loadSources();
        const failed = runs.filter(r => r.status === 'FAILED').length;
        toast(failed ? `${failed} source(s) failed` : `Ran ${runs.length} source(s)`, failed > 0);
    }));

    const fake = document.getElementById('fake-event');
    fake.addEventListener('submit', event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(fake));
        if (!data.description) delete data.description;
        run(fake.querySelector('button[type="submit"]'), async () => {
            const result = await api('POST', '/fake-events', data);
            lastManualRun.set(result.source, result);
            await loadSources();
            toast(result.created ? 'Test event stored and dispatched' : 'Test event not stored', !result.created);
        }, fake);
    });

    try {
        [state.categories, state.channels] = await Promise.all([api('GET', '/categories'), api('GET', '/channels')]);
    } catch (error) {
        showError(error);
        return;
    }
    const categoryOptions = state.categories.map(c => [c.code, c.name]);
    document.querySelector('#event-filters select[name="category"]').append(...options(categoryOptions));
    document.querySelector('#fake-event select[name="category"]').append(...options(categoryOptions));

    const initial = location.hash.slice(1);
    showTab(initial in loaders ? initial : 'users');
}

init();
