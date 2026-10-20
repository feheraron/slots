import http from 'k6/http';
import exec from 'k6/execution';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ENDPOINT = __ENV.ENDPOINT || 'get-slot';
const DURATION = __ENV.DURATION || '3m';
const USERS = Number(__ENV.USERS || 500);
const RACE_RATE = Number(__ENV.RACE_RATE || 100);
const RESULTS_DIR = __ENV.RESULTS_DIR || '';

const LOAD_USER = 2000000000;
const POOL_USER = 2100000000;
const HOT_USER = 2200000000;
const POOL_USERS = 100;
const POOL_SLOTS = 1000;
const HOT_RESOURCES = 10;
const T0 = Date.parse('2027-01-01T00:00:00Z');
const MINUTE = 60 * 1000;
const DAY = 24 * 60 * MINUTE;
const STEP = 15 * MINUTE;
const OWN_SLOT = T0 + 100 * DAY;
const OWN_MEETING = T0 + 200 * DAY;
const STATUSES = ['FREE', 'BUSY'];
const CODES = [200, 201, 204, 400, 404, 409, 500, 503];
const HEADERS = { 'Content-Type': 'application/json' };

const raceConsistent = new Rate('race_consistent');

let counter = 0;
let ownSlot = null;
let ownMeeting = null;

export const options = {
	setupTimeout: '5m',
	summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
	systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
	tags: { endpoint: ENDPOINT, testid: __ENV.TEST_ID || 'manual' },
	scenarios: {
		load: { executor: 'constant-vus', exec: 'load', vus: USERS, duration: DURATION, gracefulStop: '10s' },
		race: {
			executor: 'constant-arrival-rate', exec: 'race', rate: RACE_RATE, timeUnit: '1s', duration: DURATION,
			preAllocatedVUs: 50, maxVUs: 400, gracefulStop: '10s',
		},
	},
	thresholds: thresholds(),
};

function thresholds() {
	const result = {
		'http_req_failed{phase:measured,scenario:load}': ['rate<0.01'],
		'http_req_failed{phase:measured,scenario:race}': ['rate<0.01'],
		'race_consistent': ['rate==1'],
		'iterations{scenario:race}': ['count>=0'],
		'dropped_iterations': ['count>=0'],
		'http_reqs{phase:prep}': ['count>=0'],
	};
	for (const scenario of ['load', 'race']) {
		result[`http_req_duration{phase:measured,scenario:${scenario}}`] = ['p(95)>=0'];
		result[`http_reqs{phase:measured,scenario:${scenario}}`] = ['count>=0'];
		for (const code of CODES) {
			result[`http_reqs{phase:measured,scenario:${scenario},status:${code}}`] = ['count>=0'];
		}
	}
	return result;
}

const endpoints = {
	'create-slot': {
		route: 'POST /api/slots',
		load() {
			createSlot(loadUser(), T0 + counter++ * STEP, 'measured');
		},
		race() {
			const user = hotUser();
			const body = slotBody(user, T0 + randomInt(3) * 20 * MINUTE, 30);
			const [first, second] = parallel([
				postRequest('/api/slots', body, [201, 409], 'measured', 'POST /api/slots'),
				postRequest('/api/slots', body, [201, 409], 'measured', 'POST /api/slots'),
			]);
			raceConsistent.add(!(first.status === 201 && second.status === 201));
		},
	},
	'get-slot': {
		route: 'GET /api/slots/{id}',
		setup: () => ({ pool: poolSlots(), hot: hotSlots() }),
		load(data) {
			get(`/api/slots/${pick(data.pool)}`, [200], 'measured', 'GET /api/slots/{id}');
		},
		race(data) {
			const index = randomInt(HOT_RESOURCES);
			const id = data.hot[index];
			const [, read] = parallel([
				putRequest(`/api/slots/${id}`, rescheduleBody(T0, 30, pick(STATUSES)), [200, 409], 'prep', 'PUT /api/slots/{id}'),
				getRequest(`/api/slots/${id}`, [200], 'measured', 'GET /api/slots/{id}'),
			]);
			raceConsistent.add(read.status === 200 && STATUSES.includes(read.json('status')));
		},
	},
	'list-slots': {
		route: 'GET /api/slots',
		setup: () => ({ pool: poolSlots(), hot: hotSlots() }),
		load() {
			const from = T0 + randomInt(10) * DAY;
			get(`/api/slots?userId=${poolUser()}&from=${iso(from)}&to=${iso(from + 7 * DAY)}`,
				[200], 'measured', 'GET /api/slots');
		},
		race() {
			const user = hotUser();
			const [, list] = parallel([
				postRequest('/api/slots', slotBody(user, T0 + randomInt(200) * STEP, 10), [201, 409], 'prep', 'POST /api/slots'),
				getRequest(`/api/slots?userId=${user}&from=${iso(T0)}&to=${iso(T0 + 7 * DAY)}`,
					[200], 'measured', 'GET /api/slots'),
			]);
			raceConsistent.add(list.status === 200 && isSortedByBegin(list.json()));
		},
	},
	'block-view': {
		route: 'GET /api/slots/block-view',
		setup: () => ({ pool: poolSlots(), hot: hotSlots() }),
		load() {
			const from = T0 + randomInt(10) * DAY;
			get(`/api/slots/block-view?userId=${poolUser()}&from=${iso(from)}&to=${iso(from + (1 + randomInt(31)) * DAY)}`,
				[200], 'measured', 'GET /api/slots/block-view');
		},
		race(data) {
			const index = randomInt(HOT_RESOURCES);
			const [, view] = parallel([
				putRequest(`/api/slots/${data.hot[index]}`, rescheduleBody(T0, 30, pick(STATUSES)), [200, 409], 'prep',
					'PUT /api/slots/{id}'),
				getRequest(`/api/slots/block-view?userId=${HOT_USER + index}&from=${iso(T0 - DAY)}&to=${iso(T0 + 2 * DAY)}`,
					[200], 'measured', 'GET /api/slots/block-view'),
			]);
			raceConsistent.add(view.status === 200 && isSingleBlock(view.json(), T0, T0 + 30 * MINUTE));
		},
	},
	'reschedule': {
		route: 'PUT /api/slots/{id}',
		load() {
			const even = counter++ % 2 === 0;
			put(`/api/slots/${ensureOwnSlot()}`, rescheduleBody(OWN_SLOT + (even ? 0 : STEP), 10, even ? 'BUSY' : 'FREE'),
				[200], 'measured', 'PUT /api/slots/{id}');
		},
		race() {
			const begin = raceVuTime();
			const id = createSlot(hotUser(), begin, 'prep');
			const responses = parallel([
				putRequest(`/api/slots/${id}`, rescheduleBody(begin, 10, 'BUSY'), [200, 409], 'measured', 'PUT /api/slots/{id}'),
				putRequest(`/api/slots/${id}`, rescheduleBody(begin + 2 * MINUTE, 10, 'FREE'), [200, 409], 'measured',
					'PUT /api/slots/{id}'),
			]);
			raceConsistent.add(atLeastOne(responses, 200));
		},
	},
	'delete-slot': {
		route: 'DELETE /api/slots/{id}',
		load() {
			const id = createSlot(loadUser(), T0 + counter++ * STEP, 'prep');
			del(`/api/slots/${id}`, [204], 'measured', 'DELETE /api/slots/{id}');
		},
		race() {
			const id = createSlot(hotUser(), raceVuTime(), 'prep');
			const responses = parallel([
				deleteRequest(`/api/slots/${id}`, [204, 404, 409], 'measured', 'DELETE /api/slots/{id}'),
				deleteRequest(`/api/slots/${id}`, [204, 404, 409], 'measured', 'DELETE /api/slots/{id}'),
			]);
			raceConsistent.add(exactlyOne(responses, 204));
		},
	},
	'book': {
		route: 'POST /api/slots/{id}/meeting',
		load() {
			const id = createSlot(loadUser(), T0 + counter++ * STEP, 'prep');
			post(`/api/slots/${id}/meeting`, meetingBody(), [201], 'measured', 'POST /api/slots/{id}/meeting');
		},
		race() {
			const id = createSlot(hotUser(), raceVuTime(), 'prep');
			const responses = parallel([
				postRequest(`/api/slots/${id}/meeting`, meetingBody(), [201, 409], 'measured', 'POST /api/slots/{id}/meeting'),
				postRequest(`/api/slots/${id}/meeting`, meetingBody(), [201, 409], 'measured', 'POST /api/slots/{id}/meeting'),
			]);
			raceConsistent.add(exactlyOne(responses, 201));
		},
	},
	'edit-meeting': {
		route: 'PUT /api/slots/{id}/meeting',
		load() {
			put(`/api/slots/${ensureOwnMeeting()}/meeting`, meetingBody(), [200], 'measured', 'PUT /api/slots/{id}/meeting');
		},
		race() {
			const id = createSlot(hotUser(), raceVuTime(), 'prep');
			post(`/api/slots/${id}/meeting`, meetingBody(), [201], 'prep', 'POST /api/slots/{id}/meeting');
			const edits = [meetingBody(), meetingBody()];
			const responses = parallel(edits.map((body) => putRequest(`/api/slots/${id}/meeting`, body, [200, 409], 'measured',
				'PUT /api/slots/{id}/meeting')));
			const read = get(`/api/slots/${id}`, [200], 'prep', 'GET /api/slots/{id}');
			const stored = read.status === 200 ? read.json('meeting') : null;
			raceConsistent.add(atLeastOne(responses, 200) && stored !== null
				&& edits.some((body, i) => responses[i].status === 200 && sameMeeting(stored, body)));
		},
	},
	'cancel-meeting': {
		route: 'DELETE /api/slots/{id}/meeting',
		load() {
			const slot = ensureOwnSlot();
			post(`/api/slots/${slot}/meeting`, meetingBody(), [201], 'prep', 'POST /api/slots/{id}/meeting');
			del(`/api/slots/${slot}/meeting`, [204], 'measured', 'DELETE /api/slots/{id}/meeting');
		},
		race() {
			const id = createSlot(hotUser(), raceVuTime(), 'prep');
			post(`/api/slots/${id}/meeting`, meetingBody(), [201], 'prep', 'POST /api/slots/{id}/meeting');
			const responses = parallel([
				deleteRequest(`/api/slots/${id}/meeting`, [204, 409], 'measured', 'DELETE /api/slots/{id}/meeting'),
				deleteRequest(`/api/slots/${id}/meeting`, [204, 409], 'measured', 'DELETE /api/slots/{id}/meeting'),
			]);
			raceConsistent.add(exactlyOne(responses, 204));
		},
	},
};

const endpoint = endpoints[ENDPOINT];
if (!endpoint) {
	throw new Error(`Unknown ENDPOINT '${ENDPOINT}', expected one of ${Object.keys(endpoints).join(', ')}`);
}

export function setup() {
	return endpoint.setup ? endpoint.setup() : {};
}

export function load(data) {
	endpoint.load(data);
}

export function race(data) {
	endpoint.race(data);
}

export function handleSummary(data) {
	const load = scenarioSummary(data, 'load');
	const race = scenarioSummary(data, 'race');
	const raceInteractions = value(data, 'iterations{scenario:race}', 'count');
	const consistent = value(data, 'race_consistent', 'rate');
	const dropped = value(data, 'dropped_iterations', 'count');
	const seconds = durationSeconds(DURATION);
	const loadRow = `| ${endpoint.route} | ${load.count} | ${(load.count / seconds).toFixed(0)} | ${ms(load.avg)} | ${ms(load.p95)} | ${ms(load.p99)} | ${ms(load.max)} | ${load.statuses} | ${percent(load.failed)} |`;
	const raceRow = `| ${endpoint.route} | ${raceInteractions} | ${dropped} | ${race.count} | ${ms(race.p95)} | ${race.statuses} | ${percent(race.failed)} | ${percent(consistent)} |`;
	const text = `${ENDPOINT}: load ${load.count} requests (${(load.count / seconds).toFixed(0)}/s, p95 ${ms(load.p95)} ms, unexpected ${percent(load.failed)}), `
		+ `race ${raceInteractions} interactions (consistent ${percent(consistent)}, unexpected ${percent(race.failed)})\n`;
	const outputs = { stdout: text };
	if (RESULTS_DIR) {
		outputs[`${RESULTS_DIR}/${ENDPOINT}.json`] = JSON.stringify(data, null, 2);
		outputs[`${RESULTS_DIR}/${ENDPOINT}.load.md`] = `${loadRow}\n`;
		outputs[`${RESULTS_DIR}/${ENDPOINT}.race.md`] = `${raceRow}\n`;
	}
	return outputs;
}

function scenarioSummary(data, scenario) {
	const duration = data.metrics[`http_req_duration{phase:measured,scenario:${scenario}}`];
	const durations = duration ? duration.values : {};
	const count = value(data, `http_reqs{phase:measured,scenario:${scenario}}`, 'count');
	const byCode = CODES.map((code) => [code, value(data, `http_reqs{phase:measured,scenario:${scenario},status:${code}}`, 'count')])
		.filter(([, n]) => n > 0);
	const other = count - byCode.reduce((sum, [, n]) => sum + n, 0);
	const statuses = byCode.map(([code, n]) => `${code}×${n}`).concat(other > 0 ? [`other×${other}`] : []).join(', ') || '-';
	return {
		count, statuses,
		avg: durations.avg, p95: durations['p(95)'], p99: durations['p(99)'], max: durations.max,
		failed: value(data, `http_req_failed{phase:measured,scenario:${scenario}}`, 'rate'),
	};
}

function value(data, metric, field) {
	const found = data.metrics[metric];
	return found && found.values[field] !== undefined ? found.values[field] : 0;
}

function ms(duration) {
	return duration === undefined ? '-' : duration.toFixed(1);
}

function percent(rate) {
	return `${(rate * 100).toFixed(2)}%`;
}

function durationSeconds(text) {
	const match = /^(\d+)([smh])$/.exec(text);
	return Number(match[1]) * { s: 1, m: 60, h: 3600 }[match[2]];
}

function request(method, path, body, expected, phase, name) {
	return {
		method, url: BASE_URL + path, body: body === null ? null : JSON.stringify(body),
		params: { headers: HEADERS, tags: { phase, name }, responseCallback: http.expectedStatuses(...expected) },
	};
}

function send(spec) {
	return http.request(spec.method, spec.url, spec.body, spec.params);
}

function parallel(specs) {
	return http.batch(specs.map((spec) => [spec.method, spec.url, spec.body, spec.params]));
}

const getRequest = (path, expected, phase, name) => request('GET', path, null, expected, phase, name);
const postRequest = (path, body, expected, phase, name) => request('POST', path, body, expected, phase, name);
const putRequest = (path, body, expected, phase, name) => request('PUT', path, body, expected, phase, name);
const deleteRequest = (path, expected, phase, name) => request('DELETE', path, null, expected, phase, name);
const get = (...args) => send(getRequest(...args));
const post = (...args) => send(postRequest(...args));
const put = (...args) => send(putRequest(...args));
const del = (...args) => send(deleteRequest(...args));

function createSlot(user, begin, phase) {
	const response = post('/api/slots', slotBody(user, begin, 10), [201], phase, 'POST /api/slots');
	return response.status === 201 ? response.json('id') : null;
}

function ensureOwnSlot() {
	if (ownSlot === null) {
		ownSlot = createSlot(loadUser(), OWN_SLOT, 'prep');
	}
	return ownSlot;
}

function ensureOwnMeeting() {
	if (ownMeeting === null) {
		const slot = createSlot(loadUser(), OWN_MEETING, 'prep');
		post(`/api/slots/${slot}/meeting`, meetingBody(), [201], 'prep', 'POST /api/slots/{id}/meeting');
		ownMeeting = slot;
	}
	return ownMeeting;
}

function poolSlots() {
	const specs = Array.from({ length: POOL_SLOTS }, (_, i) => postRequest('/api/slots',
		slotBody(POOL_USER + (i % POOL_USERS), T0 + Math.floor(i / POOL_USERS) * DAY, 60), [201], 'setup', 'POST /api/slots'));
	return batched(specs).map((response) => response.json('id'));
}

function hotSlots() {
	const specs = Array.from({ length: HOT_RESOURCES }, (_, i) => postRequest('/api/slots',
		slotBody(HOT_USER + i, T0, 30), [201], 'setup', 'POST /api/slots'));
	return batched(specs).map((response) => response.json('id'));
}

function batched(specs) {
	const responses = [];
	for (let i = 0; i < specs.length; i += 50) {
		responses.push(...parallel(specs.slice(i, i + 50)));
	}
	return responses;
}

function slotBody(user, begin, minutes) {
	return { userId: user, begin: iso(begin), end: iso(begin + minutes * MINUTE) };
}

function rescheduleBody(begin, minutes, status) {
	return { begin: iso(begin), end: iso(begin + minutes * MINUTE), status };
}

function meetingBody() {
	return {
		title: pick(['Design review', 'Standup', '1:1', 'Sprint planning', 'Retro', 'Demo']),
		description: pick(['agenda', 'follow-up', 'roadmap', 'release']),
		participants: [poolUser(), poolUser()],
	};
}

function loadUser() {
	return LOAD_USER + exec.vu.idInTest;
}

function hotUser() {
	return HOT_USER + randomInt(HOT_RESOURCES);
}

function poolUser() {
	return POOL_USER + randomInt(POOL_USERS);
}

function raceVuTime() {
	return T0 + (exec.vu.idInTest * 100000 + counter++) * STEP;
}

function iso(time) {
	return new Date(time).toISOString();
}

function randomInt(bound) {
	return Math.floor(Math.random() * bound);
}

function pick(values) {
	return values[randomInt(values.length)];
}

function exactlyOne(responses, status) {
	return responses.filter((response) => response.status === status).length === 1;
}

function atLeastOne(responses, status) {
	return responses.some((response) => response.status === status);
}

function sameMeeting(stored, body) {
	const participants = [...new Set(body.participants)].sort((a, b) => a - b);
	return stored.title === body.title && stored.description === body.description
		&& JSON.stringify(stored.participants) === JSON.stringify(participants);
}

function isSortedByBegin(items) {
	return items.every((item, i) => i === 0 || items[i - 1].begin <= item.begin);
}

function isSingleBlock(blocks, begin, end) {
	return blocks.length === 1
		&& Date.parse(blocks[0].begin) === begin
		&& Date.parse(blocks[0].end) === end
		&& STATUSES.includes(blocks[0].status);
}
