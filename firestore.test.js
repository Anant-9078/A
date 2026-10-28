const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- Security Bounds Tests ---

test("Unauthenticated: cannot read or write users", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set({
    username: "Alice",
    status: "online"
  }));
});

test("Authenticated: user can create and update own profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set({
    username: "Alice",
    status: "online",
    age: 25,
    country: "USA",
    language: "English"
  }));

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).update({
    status: "searching"
  }));
});

test("Authenticated: user cannot modify another user's profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).set({
    username: "HackedBob",
    status: "online"
  }));
});

test("Queue: user can join queue and Bob can match Alice", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("matchingQueue").doc(ALICE_UID).set({
    userId: ALICE_UID,
    username: "Alice",
    status: "waiting",
    country: "USA",
    language: "English"
  }));

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  // Bob updates Alice's queue entry to matched
  await assertSucceeds(bobDb.collection("matchingQueue").doc(ALICE_UID).update({
    status: "matched",
    matchedWith: BOB_UID,
    matchId: "match_123"
  }));
});

test("Matches: members can create, read, and send messages", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const matchId = "match_alice_bob";

  await assertSucceeds(aliceDb.collection("matches").doc(matchId).set({
    id: matchId,
    user1Id: ALICE_UID,
    user2Id: BOB_UID,
    memberUids: [ALICE_UID, BOB_UID],
    status: "active"
  }));

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(bobDb.collection("matches").doc(matchId).get());

  // Charlie is not a member and cannot read
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  await assertFails(charlieDb.collection("matches").doc(matchId).get());

  // Alice sends message
  await assertSucceeds(aliceDb.collection("matches").doc(matchId).collection("messages").doc("msg1").set({
    matchId: matchId,
    senderId: ALICE_UID,
    text: "Hello Bob!",
    memberUids: [ALICE_UID, BOB_UID]
  }));

  // Charlie cannot read or send messages in match
  await assertFails(charlieDb.collection("matches").doc(matchId).collection("messages").doc("msg1").get());
  await assertFails(charlieDb.collection("matches").doc(matchId).collection("messages").doc("msg2").set({
    matchId: matchId,
    senderId: CHARLIE_UID,
    text: "Intruder message",
    memberUids: [ALICE_UID, BOB_UID]
  }));
});

test("Reports: user can submit report and read own report", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("reports").doc("rep1").set({
    reporterId: ALICE_UID,
    reportedUserId: BOB_UID,
    reason: "Inappropriate behavior",
    details: "Used bad language"
  }));

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("reports").doc("rep1").get());
});
