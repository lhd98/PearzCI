// Kiểm tra cú pháp Groovy mà không cần Jenkins: chỉ parse tới phase
// CONVERSION nên không cần resolve class/plugin của Jenkins.
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.Phases

def failed = false
args.each { path ->
    def unit = new CompilationUnit()
    unit.addSource(new File(path))
    try {
        unit.compile(Phases.CONVERSION)
        println "OK    ${path}"
    } catch (CompilationFailedException exception) {
        failed = true
        println "ERROR ${path}"
        println exception.message
    }
}
System.exit(failed ? 1 : 0)
