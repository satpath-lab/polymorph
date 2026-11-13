#include "volt.h"
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <string.h>

static int inited;

static PyObject *py_host(PyObject *self, PyObject *args) {
    const char *s;
    if (!PyArg_ParseTuple(args, "s", &s)) return NULL;
    char *r;
    Py_BEGIN_ALLOW_THREADS
    r = volt_host(s);
    Py_END_ALLOW_THREADS
    PyObject *o = PyUnicode_FromString(r); free(r); return o;
}

static PyObject *py_read(PyObject *self, PyObject *args) {
    const char *p;
    if (!PyArg_ParseTuple(args, "s", &p)) return NULL;
    size_t n; char *b = volt_read(p, &n);
    if (!b) Py_RETURN_NONE;
    PyObject *o = PyUnicode_DecodeUTF8(b, n, "replace"); free(b); return o;
}

static PyMethodDef methods[] = { {"host", py_host, METH_VARARGS, NULL}, {"read", py_read, METH_VARARGS, NULL}, {NULL, NULL, 0, NULL} };
static struct PyModuleDef mod = { PyModuleDef_HEAD_INIT, "_volt", NULL, -1, methods };
static PyObject *init_volt(void) { return PyModule_Create(&mod); }

static char *pyerr(void) {
    PyObject *t, *v, *tb;
    PyErr_Fetch(&t, &v, &tb);
    PyErr_NormalizeException(&t, &v, &tb);
    PyObject *s = v ? PyObject_Str(v) : NULL;
    const char *c = s ? PyUnicode_AsUTF8(s) : "python error";
    char *o = volt_strdup(c ? c : "python error");
    Py_XDECREF(s); Py_XDECREF(t); Py_XDECREF(v); Py_XDECREF(tb);
    return o;
}

static void *open_(const char *dir, const char *main, const char *harness, char **err) {
    if (!inited) {
        PyImport_AppendInittab("_volt", init_volt);
        PyConfig c;
        PyConfig_InitIsolatedConfig(&c);
        c.install_signal_handlers = 0;
        const char *home = getenv("VOLT_PYHOME");
        PyStatus st = PyConfig_SetBytesString(&c, &c.home, home ? home : "");
        if (!PyStatus_Exception(st)) st = Py_InitializeFromConfig(&c);
        PyConfig_Clear(&c);
        if (PyStatus_Exception(st)) { *err = volt_strdup(st.err_msg ? st.err_msg : "python init failed"); return NULL; }
        PyEval_SaveThread();
        inited = 1;
    }
    PyGILState_STATE g = PyGILState_Ensure();
    PyObject *ns = PyDict_New();
    PyDict_SetItemString(ns, "__builtins__", PyEval_GetBuiltins());
    PyObject *d = PyUnicode_FromString(dir), *m = PyUnicode_FromString(main);
    PyDict_SetItemString(ns, "__VOLT_DIR", d); PyDict_SetItemString(ns, "__VOLT_MAIN", m);
    Py_DECREF(d); Py_DECREF(m);
    size_t n; char *src = volt_read(harness, &n);
    PyObject *r = src ? PyRun_String(src, Py_file_input, ns, ns) : NULL;
    free(src);
    if (!r) { *err = PyErr_Occurred() ? pyerr() : volt_strdup("harness missing"); Py_DECREF(ns); PyGILState_Release(g); return NULL; }
    Py_DECREF(r);
    PyGILState_Release(g);
    return ns;
}

static char *call_(void *h, const char *fn, const char *arg) {
    PyGILState_STATE g = PyGILState_Ensure();
    PyObject *f = PyDict_GetItemString((PyObject *)h, "__volt");
    PyObject *r = f ? PyObject_CallFunction(f, "ss", fn, arg) : NULL;
    char *out;
    if (!r) { char *e = pyerr(); out = volt_strdup("{\"error\":\"python engine error\"}"); free(e); }
    else { const char *s = PyUnicode_AsUTF8(r); out = volt_strdup(s ? s : "null"); Py_DECREF(r); }
    PyGILState_Release(g);
    return out;
}

static void close_(void *h) { PyGILState_STATE g = PyGILState_Ensure(); Py_DECREF((PyObject *)h); PyGILState_Release(g); }
const VoltLang VOLT_LANG = { open_, call_, close_ };
